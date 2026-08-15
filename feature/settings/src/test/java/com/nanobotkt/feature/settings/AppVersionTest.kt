package com.nanobotkt.feature.settings

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppVersionTest {
    @Test
    fun `numeric comparison treats 0 1 10 as newer than 0 1 9`() {
        assertTrue(
            isAppUpdateAvailable(
                currentVersionName = "0.1.9",
                currentChannel = AppReleaseChannel.RELEASE,
                remoteVersionName = "0.1.10",
                remoteChannel = AppReleaseChannel.RELEASE,
            ),
        )
    }

    @Test
    fun `formal release upgrades to a newer formal release`() {
        assertTrue(
            isAppUpdateAvailable(
                currentVersionName = "0.1.5",
                currentChannel = AppReleaseChannel.RELEASE,
                remoteVersionName = "0.1.6",
                remoteChannel = AppReleaseChannel.RELEASE,
            ),
        )
    }

    @Test
    fun `dev release upgrades only within dev channel`() {
        assertTrue(
            isAppUpdateAvailable(
                currentVersionName = "0.1.5-dev",
                currentChannel = AppReleaseChannel.DEV,
                remoteVersionName = "0.1.6-dev",
                remoteChannel = AppReleaseChannel.DEV,
            ),
        )
        assertFalse(
            isAppUpdateAvailable(
                currentVersionName = "0.1.5",
                currentChannel = AppReleaseChannel.RELEASE,
                remoteVersionName = "0.1.6-dev",
                remoteChannel = AppReleaseChannel.DEV,
            ),
        )
    }

    @Test
    fun `same numeric version does not offer update`() {
        assertFalse(
            isAppUpdateAvailable(
                currentVersionName = "0.1.5-dev",
                currentChannel = AppReleaseChannel.DEV,
                remoteVersionName = "0.1.5-dev",
                remoteChannel = AppReleaseChannel.DEV,
            ),
        )
        assertFalse(
            isAppUpdateAvailable(
                currentVersionName = "0.1.5",
                currentChannel = AppReleaseChannel.RELEASE,
                remoteVersionName = "0.1.5",
                remoteChannel = AppReleaseChannel.RELEASE,
            ),
        )
    }

    @Test
    fun `malformed remote version is rejected instead of guessed`() {
        assertFalse(
            isAppUpdateAvailable(
                currentVersionName = "0.1.5",
                currentChannel = AppReleaseChannel.RELEASE,
                remoteVersionName = "latest",
                remoteChannel = AppReleaseChannel.RELEASE,
            ),
        )
    }
}
