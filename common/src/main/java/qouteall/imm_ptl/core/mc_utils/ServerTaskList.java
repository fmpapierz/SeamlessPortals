package qouteall.imm_ptl.core.mc_utils;

import com.warwa.seamlessportals.platform.Platform;
import net.minecraft.server.MinecraftServer;
import qouteall.imm_ptl.core.IPGlobal;
import qouteall.imm_ptl.core.IPPerServerInfo;
import qouteall.q_misc_util.my_util.MyTaskList;

public class ServerTaskList {
    public static void init() {
        Platform.get().onServerTickEnd(server -> { // NF-PARITY W9
            of(server).processTasks();
        });
        
        IPGlobal.SERVER_CLEANUP_EVENT.register(server -> {
            of(server).forceClearTasks();
        });
    }
    
    // the tasks are executed after ticking. will be cleared when server closes
    public static MyTaskList of(MinecraftServer server) {
        return IPPerServerInfo.of(server).taskList;
    }
}
