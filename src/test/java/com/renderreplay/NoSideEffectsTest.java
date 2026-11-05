package com.renderreplay;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.Test;

/**
 * Guard for the "recording must not change what the client sends to the server" requirement.
 * Fails the build if the plugin source starts using network, process, or input-injection APIs, or calls
 * any Client method other than the read-only getters and setDrawCallbacks.
 */
public class NoSideEffectsTest
{
	/** PlaybackKeys only consumes the three transport keys during playback; it is the one file allowed to name KeyEvent. */
	private static final String INPUT_BLOCK = "PlaybackKeys.java";

	private static String allSource() throws IOException
	{
		return allSource(false);
	}

	private static String allSource(boolean skipInputBlock) throws IOException
	{
		try (Stream<Path> s = Files.walk(Paths.get("src/main/java")))
		{
			List<Path> files = s.filter(p -> p.toString().endsWith(".java"))
				.filter(p -> !(skipInputBlock && p.toString().endsWith(INPUT_BLOCK)))
				.collect(Collectors.toList());
			StringBuilder sb = new StringBuilder();
			for (Path f : files)
			{
				sb.append(new String(Files.readAllBytes(f), StandardCharsets.UTF_8)).append('\n');
			}
			return sb.toString();
		}
	}

	// Note: Runtime.getRuntime() alone is allowed (the recorder reads maxMemory() for its retention budget);
	// what must never appear is launching a process (exec / ProcessBuilder).
	@Test
	public void noNetworkProcessOrInputApis() throws IOException
	{
		String src = allSource(false);
		for (String banned : Arrays.asList("java.net", "Socket", "HttpURL", "okhttp", "WebSocket", "InetAddress",
			"ProcessBuilder", "getRuntime().exec", "Runtime.exec", "invokeMenuAction", "MenuEntry", "java.awt.Robot",
			"dispatchEvent", "sendPacket"))
		{
			assertTrue("plugin must not use " + banned, !src.contains(banned));
		}
		assertTrue("no mouse handling at all", !allSource(false).contains("MouseEvent") && !allSource(false).contains("MouseListener"));
		assertTrue("only the playback input block may use KeyEvent", !allSource(true).contains("KeyEvent"));
		// it may consume events but never create or dispatch them
		String block = new String(Files.readAllBytes(Paths.get("src/main/java/com/renderreplay/" + INPUT_BLOCK)), StandardCharsets.UTF_8);
		assertTrue("input block must not construct events", !block.contains("new KeyEvent"));
	}

	@Test
	public void clientCallsAreReadOnlyExceptDrawCallbacks() throws IOException
	{
		Matcher m = Pattern.compile("client\\.([a-zA-Z]+)\\(").matcher(allSource());
		List<String> bad = new ArrayList<>();
		while (m.find())
		{
			String name = m.group(1);
			if (!name.startsWith("get") && !name.equals("setDrawCallbacks"))
			{
				bad.add(name);
			}
		}
		assertEquals("non-read-only Client calls", new ArrayList<String>(), bad);
	}

	/**
	 * RuneLite plugin hub rules (wiki "Rejected or Rolled Back Features" > "Forbidden language features", example-plugin
	 * AGENTS.md): no reflection, JNI/JNA, Unsafe, external processes, classloading or runtime code generation (dynamic
	 * proxies included), no Java (de)serialization. Comments are ignored; code is what counts.
	 */
	@Test
	public void noReflectionOrRuntimeCodeGeneration() throws IOException
	{
		String code = allSource().replaceAll("(?s)/\\*.*?\\*/", "").replaceAll("(?m)//.*$", "");
		for (String banned : Arrays.asList("java.lang.reflect", "Proxy.newProxyInstance", "InvocationHandler", "setAccessible", "Class.forName",
			"getDeclaredMethod", "getDeclaredField", "getDeclaredConstructor", ".getMethod(", ".getMethods(", ".getField(", ".newInstance(",
			"ClassLoader", "defineClass", "MethodHandle", "sun.misc", "Unsafe", "System.load", "System.loadLibrary", "com.sun.jna",
			"ObjectInputStream", "ObjectOutputStream", "javax.tools", "java.lang.invoke"))
		{
			assertTrue("plugin hub rules forbid " + banned, !code.contains(banned));
		}
	}
}
