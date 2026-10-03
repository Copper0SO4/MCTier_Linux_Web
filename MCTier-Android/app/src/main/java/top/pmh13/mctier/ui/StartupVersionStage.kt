package top.pmh13.mctier.ui

internal enum class StartupVersionStage { Mandatory, Checking, Optional, Ready }

/** Consent gates the entire host; a late server rejection preempts every lower-priority prompt. */
internal fun startupVersionStage(mandatory: Boolean, checked: Boolean, optional: Boolean): StartupVersionStage = when {
    mandatory -> StartupVersionStage.Mandatory
    !checked -> StartupVersionStage.Checking
    optional -> StartupVersionStage.Optional
    else -> StartupVersionStage.Ready
}
