package com.shilapi.xcertplay.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UpdatePolicyTest {
    @Test
    fun aReleaseBuildMayCheckForUpdates() {
        assertNull(UpdatePolicy.disabledReason(debuggable = false))
    }

    @Test
    fun aTestBuildSkipsTheCheckWithAStatedReason() {
        assertEquals("disabled in test builds", UpdatePolicy.disabledReason(debuggable = true))
    }

    /**
     * Pins the repository the app checks. It must be the fork whose workflow publishes the releases,
     * not upstream DiPlay: those releases carry no HiCar code and are signed with another key.
     */
    @Test
    fun updatesAreLookedUpInTheForkThatPublishesTheBuilds() {
        val repository = UpdateRepository()
        assertEquals("anye1998", repository.owner)
        assertEquals("HiPlay", repository.name)
        assertEquals("https://api.github.com/repos/anye1998/HiPlay/releases?per_page=3", repository.apiUrl)
        assertEquals(
            "https://raw.githubusercontent.com/anye1998/HiPlay/main/version.json",
            repository.manifestUrl,
        )
    }
}
