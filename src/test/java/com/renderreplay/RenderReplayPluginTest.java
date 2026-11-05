package com.renderreplay;

import net.runelite.client.RuneLite;
import net.runelite.client.externalplugins.ExternalPluginManager;

public class RenderReplayPluginTest
{
	public static void main(String[] args) throws Exception
	{
		ExternalPluginManager.loadBuiltin(RenderReplayPlugin.class);
		RuneLite.main(args);
	}
}
