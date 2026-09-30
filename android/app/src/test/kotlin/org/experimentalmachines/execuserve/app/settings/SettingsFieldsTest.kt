package org.experimentalmachines.execuserve.app.settings

import org.experimentalmachines.execuserve.host.HostSettings
import org.junit.Assert.assertEquals
import org.junit.Test

class SettingsFieldsTest {
    /**
     * Every setting has a stored field. `threads` once had none: the adb override was read,
     * written nowhere, and every run used the default (found on the POCO, 2026-09-30).
     */
    @Test
    fun everySettingIsStored() {
        val settings = HostSettings::class.java.declaredFields.filter { !java.lang.reflect.Modifier.isStatic(it.modifiers) }
        assertEquals(settings.map { it.name }.sorted().toString(), settings.size, SettingsStore.FIELDS.size)
    }
}
