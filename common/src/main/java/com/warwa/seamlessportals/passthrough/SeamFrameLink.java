package com.warwa.seamlessportals.passthrough;

import com.mojang.logging.LogUtils;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * THE DORMANT FRAME LINK — how a portal frame keeps its partner after the portal is gone.
 *
 * <p><b>The problem this exists to solve.</b> The user's frame-mirroring rule is: breaking obsidian on
 * one side breaks the corresponding obsidian on the other, and repairing and lighting one side also
 * repairs and lights the other, re-linking them. The first half is easy while both portals live. The
 * second half is not, and it is the whole difficulty: {@link SeamRegistry} bindings are DERIVED from
 * live {@code Portal} entities, so the instant both portals tear down nothing in the world knows that
 * this frame and that frame were ever partners. A repair then has nothing to mirror through.
 *
 * <p><b>The answer: record the pairing while it is still knowable, and persist it.</b> Frame links are
 * written when a portal binds — the one moment both sides are alive and the mapping is certain — and
 * survive in level storage afterwards. A frame broken and repaired hours later, with no portal alive
 * at either end, still finds its partner.
 *
 * <p><b>Why frame cells and aperture cells are kept apart.</b> An aperture binding is about a hole:
 * it is symmetric, it carries block state across, and it dies with the portal. A frame link is about
 * a wall: it carries only "this obsidian corresponds to that obsidian", and it must OUTLIVE the
 * portal to be useful at all. Folding them together would have forced aperture bindings to persist
 * too, which is exactly what {@link SeamRegistry} deliberately avoids — derived state cannot drift
 * from the portals, and persisted state can.
 */
public class SeamFrameLink extends SavedData {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** One frame cell's partner, in another dimension. */
    public record Link(BlockPos from, ResourceKey<Level> toDim, BlockPos to) {
        public static final Codec<Link> CODEC = RecordCodecBuilder.create(
            i -> i.group(
                BlockPos.CODEC.fieldOf("from").forGetter(Link::from),
                ResourceKey.codec(net.minecraft.core.registries.Registries.DIMENSION)
                    .fieldOf("to_dim").forGetter(Link::toDim),
                BlockPos.CODEC.fieldOf("to").forGetter(Link::to)
            ).apply(i, Link::new)
        );
    }

    public static final Codec<SeamFrameLink> CODEC = RecordCodecBuilder.create(
        i -> i.group(
            Link.CODEC.listOf().fieldOf("links").forGetter(SeamFrameLink::linkList)
        ).apply(i, SeamFrameLink::new)
    );

    public static final SavedDataType<SeamFrameLink> TYPE = new SavedDataType<>(
        Identifier.fromNamespaceAndPath("seamlessportals", "seam_frame_link"),
        SeamFrameLink::new, CODEC, DataFixTypes.LEVEL
    );

    /** Cap so a pathological rebind loop cannot grow the save without bound. */
    private static final int MAX_LINKS = 16384;

    private final Map<Long, Link> links = new HashMap<>();

    public SeamFrameLink() {
    }

    private SeamFrameLink(List<Link> initial) {
        for (Link l : initial) {
            links.put(l.from().asLong(), l);
        }
    }

    private List<Link> linkList() {
        return List.copyOf(links.values());
    }

    public static SeamFrameLink get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(TYPE);
    }

    /** Record (or refresh) the partner of a frame cell. Idempotent. */
    public static void record(ServerLevel level, BlockPos from, ResourceKey<Level> toDim, BlockPos to) {
        SeamFrameLink data = get(level);
        if (data.links.size() >= MAX_LINKS && !data.links.containsKey(from.asLong())) {
            LOGGER.warn("[RS-FRAME-LINK] {} is at the {}-link cap — not recording {}",
                level.dimension().identifier(), MAX_LINKS, from);
            return;
        }
        Link existing = data.links.get(from.asLong());
        Link fresh = new Link(from.immutable(), toDim, to.immutable());
        if (existing != null && existing.equals(fresh)) {
            return;
        }
        data.links.put(from.asLong(), fresh);
        data.setDirty();
    }

    @Nullable
    public static Link lookup(ServerLevel level, BlockPos pos) {
        SeamFrameLink data = level.getDataStorage().get(TYPE);
        return data == null ? null : data.links.get(pos.asLong());
    }

    public static boolean hasAny(ServerLevel level) {
        SeamFrameLink data = level.getDataStorage().get(TYPE);
        return data != null && !data.links.isEmpty();
    }

    public int size() {
        return links.size();
    }
}
