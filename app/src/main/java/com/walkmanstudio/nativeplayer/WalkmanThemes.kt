// SPDX-License-Identifier: GPL-3.0-or-later
package com.walkmanstudio.nativeplayer

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color

enum class WalkmanTheme(val id:String,val model:String,val dark:Boolean,
    val body:Color,val face:Color,val accent:Color,val ink:Color,val display:Color,val displayInk:Color) {
    MIO("mio", "Than và đồng", true, Color(0xFF141516), Color(0xFF292725),
        Color(0xFFE1B980), Color(0xFFF3F5F7), Color(0xFFD4B88A), Color(0xFF242019)),
    TPS_L2("tpsl2","Xanh đêm",true,Color(0xFF142F48),Color(0xFF234B69),Color(0xFFFFC082),Color(0xFFF2F5F7),Color(0xFFDDE5D7),Color(0xFF18261C)),
    DD9("dd9","Than chì",true,Color(0xFF101413),Color(0xFF29332E),Color(0xFFCDE2CE),Color(0xFFF0F5EE),Color(0xFFBDCF9F),Color(0xFF172312)),
    RH1("rh1","Bạc lạnh",false,Color(0xFFE2E5E8),Color(0xFFF6F7F9),Color(0xFF204F73),Color(0xFF171E25),Color(0xFF112536),Color(0xFFC1E5FF)),
    WM1Z("wm1z","Đồng ấm",true,Color(0xFF302513),Color(0xFF493923),Color(0xFFF1D293),Color(0xFFFFF1D7),Color(0xFF191B16),Color(0xFFF2E6BF));
    val colors:ColorScheme get() {
        val base=if(dark)StudioDark else StudioLight
        val onAccent=if(dark)body else Color.White
        return base.copy(primary=accent,onPrimary=onAccent,primaryContainer=face,onPrimaryContainer=ink,
            secondary=accent,onSecondary=onAccent,secondaryContainer=face,onSecondaryContainer=ink,
            background=body,onBackground=ink,surface=body,onSurface=ink,surfaceVariant=face,onSurfaceVariant=ink,
            surfaceContainerLowest=body,surfaceContainerLow=body,surfaceContainer=face,surfaceContainerHigh=face,surfaceContainerHighest=face)
    }
    companion object {fun find(id:String?)=entries.firstOrNull{it.id==id}?:TPS_L2}
}
