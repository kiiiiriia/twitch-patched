package dev.twitchpatches.patches.twitch.subvods

import app.morphe.patcher.patch.bytecodePatch
import com.android.tools.smali.dexlib2.Opcode
import dev.twitchpatches.patches.twitch.settings.settingsPatch
import dev.twitchpatches.patches.twitch.shared.*

private const val SUB_VOD_RUNTIME = "Ldev/twitchpatches/extension/subvods/SubVodRuntime;"

// Approach adapted from TwitchNoSub (https://github.com/besuper/TwitchNoSub, GPL-3.0).
@Suppress("unused")
val subOnlyVodsPatch = bytecodePatch(
    name = "Watch sub-only VODs",
    description = "Plays subscriber-only VODs and highlights from public video storage when Twitch refuses the manifest.",
    default = true,
) {
    compatibleWith(TwitchTarget.compatibility)
    dependsOn(settingsPatch)
    execute {
        applyIvsHttp(resolveIvsHttp(), "$SUB_VOD_RUNTIME->wrap(${IVS_NET}HttpClient;)${IVS_NET}HttpClient;")
        val onCreate = classDefBy("Ltv/twitch/android/app/consumer/TwitchApplication;").methods.filter {
            it.name == "onCreate" && it.isInstance(emptyList(), "V")
        }.uniqueHook("sub-only VOD preferences initialization")
        val method = mutableClassDefBy(onCreate.definingClass).methods.single { it.reference == onCreate.reference }
        method.code().withIndex().filter { it.value.opcode == Opcode.RETURN_VOID }.map { it.index }.asReversed().forEach {
            method.insertAtReturn(it, "invoke-static/range {p0 .. p0}, $SUB_VOD_RUNTIME->initialize(Landroid/app/Application;)V")
        }
    }
}
