package com.warwa.seamlessportals.mixin;

import net.minecraft.server.level.TicketType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * Invoker for {@code TicketType.register}, which is private in 26.1.2.
 *
 * <p>In 26.1.2, {@link TicketType} values must be registered in the
 * {@code BuiltInRegistries.TICKET_TYPE} registry before use — ad-hoc
 * {@code new TicketType(...)} instances cause
 * {@code IllegalStateException: Unregistered holder ...} when handed to
 * {@code DistanceManager}. We register our own portal-view ticket by
 * forwarding through this invoker at mod init.
 */
@Mixin(TicketType.class)
public interface TicketTypeInvoker {

    @Invoker("register")
    static TicketType seamlessportals$invokeRegister(String name, long timeout, int flags) {
        throw new AssertionError();
    }
}
