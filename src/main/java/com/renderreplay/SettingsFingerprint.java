package com.renderreplay;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import net.runelite.client.config.ConfigManager;

/**
 * Hash of the player's plugin settings, so a recording can warn when it is played with different settings (plugin
 * overlays are part of what gets recorded, so different settings mean a different picture).
 * Reads settings only. Input is "group.key" -> value; the result has one hash per plugin group plus an overall hash,
 * so a mismatch can name the plugins that changed.
 */
final class SettingsFingerprint
{
	/** Groups that never affect the recorded picture (this plugin's own settings, and values RuneLite rewrites on its own such as the last-seen-online time). */
	private static final java.util.Set<String> IGNORED_GROUPS = new TreeSet<>(java.util.Arrays.asList("renderreplay", "lastSeenOnline"));

	final String overall;
	final Map<String, String> groups;

	private SettingsFingerprint(String overall, Map<String, String> groups)
	{
		this.overall = overall;
		this.groups = groups;
	}

	/** Rebuilds a fingerprint saved in a recording's manifest. */
	static SettingsFingerprint fromGroups(String overall, Map<String, String> groups)
	{
		return new SettingsFingerprint(overall, new TreeMap<>(groups));
	}

	static SettingsFingerprint of(Map<String, String> settings)
	{
		Map<String, StringBuilder> perGroup = new TreeMap<>();
		for (Map.Entry<String, String> e : new TreeMap<>(settings).entrySet())
		{
			String key = e.getKey();
			int dot = key.indexOf('.');
			if (dot <= 0)
			{
				continue;
			}
			String group = key.substring(0, dot);
			String name = key.substring(dot + 1);
			if (IGNORED_GROUPS.contains(group) || !relevant(group, name))
			{
				continue;
			}
			perGroup.computeIfAbsent(group, g -> new StringBuilder()).append(name).append('=').append(e.getValue()).append('\n');
		}
		Map<String, String> hashes = new TreeMap<>();
		StringBuilder all = new StringBuilder();
		for (Map.Entry<String, StringBuilder> e : perGroup.entrySet())
		{
			String h = sha(e.getValue().toString());
			hashes.put(e.getKey(), h);
			all.append(e.getKey()).append(':').append(h).append('\n');
		}
		return new SettingsFingerprint(sha(all.toString()), hashes);
	}

	/** In the core "runelite" group only the plugin on/off switches matter; everything else is window/UI state. */
	private static boolean relevant(String group, String name)
	{
		return !group.equals("runelite") || name.endsWith("plugin");
	}

	/** Groups whose hash differs, or that exist on one side only, sorted. */
	static List<String> diff(Map<String, String> recorded, Map<String, String> current)
	{
		TreeSet<String> names = new TreeSet<>();
		names.addAll(recorded.keySet());
		names.addAll(current.keySet());
		List<String> out = new ArrayList<>();
		for (String n : names)
		{
			if (!java.util.Objects.equals(recorded.get(n), current.get(n)))
			{
				out.add(n);
			}
		}
		return out;
	}

	/** Reads every stored setting (getters only). */
	static Map<String, String> collect(ConfigManager cm)
	{
		Map<String, String> out = new TreeMap<>();
		for (String full : cm.getConfigurationKeys(""))
		{
			int dot = full.indexOf('.');
			if (dot <= 0)
			{
				continue;
			}
			String v = cm.getConfiguration(full.substring(0, dot), full.substring(dot + 1));
			if (v != null)
			{
				out.put(full, v);
			}
		}
		return out;
	}

	private static String sha(String s)
	{
		try
		{
			byte[] d = MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
			StringBuilder sb = new StringBuilder();
			for (int i = 0; i < 8; i++)
			{
				sb.append(String.format("%02x", d[i]));
			}
			return sb.toString();
		}
		catch (NoSuchAlgorithmException e)
		{
			throw new IllegalStateException(e);
		}
	}
}
