package qouteall.imm_ptl.core.compat;

import com.warwa.seamlessportals.platform.Platform;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import qouteall.q_misc_util.Helper;

@Environment(EnvType.CLIENT)
public class IPFlywheelCompat {
    
    public static boolean isFlywheelPresent = false;
    
    public static void init(){
        if (Platform.get().isModLoaded("flywheel")) { // NF-PARITY W8
            Helper.log("Flywheel is present");
        }
        
    }
    
}
