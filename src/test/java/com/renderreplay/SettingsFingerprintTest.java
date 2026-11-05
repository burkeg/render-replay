package com.renderreplay;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import org.junit.Test;

public class SettingsFingerprintTest
{
	private static Map<String, String> base()
	{
		Map<String, String> m = new HashMap<>();
		m.put("npchighlight.drawOutline", "true");
		m.put("grounditems.showValue", "true");
		m.put("runelite.npchighlightplugin", "true");
		m.put("runelite.windowBounds", "0,0,800,600");
		m.put("renderreplay.anything", "1");
		return m;
	}

	@Test
	public void sameSettingsSameHashRegardlessOfOrderAndIgnoredKeys()
	{
		Map<String, String> a = base();
		Map<String, String> b = new java.util.LinkedHashMap<>();
		b.put("grounditems.showValue", "true");
		b.put("runelite.npchighlightplugin", "true");
		b.put("npchighlight.drawOutline", "true");
		b.put("runelite.windowBounds", "9,9,1,1");   // window state is ignored
		b.put("renderreplay.anything", "2");          // our own settings are ignored
		assertEquals(SettingsFingerprint.of(a).overall, SettingsFingerprint.of(b).overall);
	}

	@Test
	public void changedSettingNamesTheGroup()
	{
		Map<String, String> a = base();
		Map<String, String> b = base();
		b.put("npchighlight.drawOutline", "false");
		SettingsFingerprint fa = SettingsFingerprint.of(a), fb = SettingsFingerprint.of(b);
		assertNotEquals(fa.overall, fb.overall);
		assertEquals(Arrays.asList("npchighlight"), SettingsFingerprint.diff(fa.groups, fb.groups));
	}

	@Test
	public void pluginToggleCountsButOtherRuneliteKeysDoNot()
	{
		Map<String, String> a = base();
		Map<String, String> b = base();
		b.put("runelite.grounditemsplugin", "true");   // newly enabled plugin
		assertTrue(SettingsFingerprint.diff(SettingsFingerprint.of(a).groups, SettingsFingerprint.of(b).groups).contains("runelite"));
	}

	@Test
	public void selfRewrittenValuesDoNotTriggerAWarning()
	{
		Map<String, String> a = base();
		Map<String, String> b = base();
		a.put("lastSeenOnline.Some Player", "1789840000000");
		b.put("lastSeenOnline.Some Player", "1789841234567");
		assertEquals(SettingsFingerprint.of(a).overall, SettingsFingerprint.of(b).overall);
	}
}
