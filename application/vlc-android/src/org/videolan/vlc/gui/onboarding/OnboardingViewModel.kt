package org.videolan.vlc.gui.onboarding

import androidx.appcompat.app.AppCompatDelegate
import androidx.lifecycle.ViewModel

class OnboardingViewModel : ViewModel() {
    var permissionAlreadyAsked: Boolean = false
    var notificationPermissionAlreadyAsked: Boolean = false
    var scanStorages = true
    var permissionType: PermissionType = PermissionType.ALL

    var theme = AppCompatDelegate.MODE_NIGHT_YES
    var currentFragment = FragmentName.WELCOME
}

enum class PermissionType {
    NONE, MEDIA, ALL
}
