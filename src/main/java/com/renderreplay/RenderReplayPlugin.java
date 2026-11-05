package com.renderreplay;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.awt.Canvas;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Toolkit;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.StringSelection;
import java.awt.datatransfer.UnsupportedFlavorException;
import java.awt.image.BufferedImage;
import java.io.BufferedWriter;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;
import javax.inject.Inject;
import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.BufferProvider;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.Player;
import net.runelite.api.Scene;
import net.runelite.api.events.BeforeRender;
import net.runelite.api.hooks.DrawCallbacks;
import net.runelite.client.RuneLite;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.input.KeyManager;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.ClientToolbar;
import net.runelite.client.ui.NavigationButton;

/**
 * Records the game viewport's renderer input into one of four in-memory slots, and plays a slot back by issuing the
 * recorded calls in place of the live ones. Everything stays in local files; nothing is sent anywhere.
 */
@Slf4j
@PluginDescriptor(
	name = "Render Replay",
	description = "Record what the client sends to the GPU renderer and replay it in the game viewport",
	tags = {"replay", "record", "gpu"}
)
public class RenderReplayPlugin extends Plugin
{
	enum State
	{
		IDLE, RECORDING, PLAYING
	}

	static final int SLOT_COUNT = 4;
	// Memory for all slots together.
	private static final long TOTAL_BUDGET = DrawRecorder.budgetBytes;
	private static final String CLIP_PREFIX = "RENDERREPLAY1:";
	private static final String CLIP_FILE_PREFIX = "RENDERREPLAYFILE1:";
	// Clipboard text of a few megabytes can crash the JVM, so a bigger recording goes to a file and the clipboard
	// carries its path.
	private static final long CLIP_MAX_BYTES = 900 * 1024;

	private static final class Slot
	{
		final DrawCallbacks recorder;
		final long createdMs = System.currentTimeMillis();
		final SettingsFingerprint settings;
		final int scale, canvasW, canvasH;

		Slot(DrawCallbacks recorder, SettingsFingerprint settings, int scale, int canvasW, int canvasH)
		{
			this.recorder = recorder;
			this.settings = settings;
			this.scale = scale;
			this.canvasW = canvasW;
			this.canvasH = canvasH;
		}
	}

	@Inject
	private Client client;
	@Inject
	private ClientThread clientThread;
	@Inject
	private ConfigManager configManager;
	@Inject
	private ClientToolbar clientToolbar;
	@Inject
	private KeyManager keyManager;
	@Inject
	private ScheduledExecutorService executor;
	@Inject
	private Gson gson;

	private final DrawRecorder.Probe probe = new DrawRecorder.Probe()
	{
		@Override
		public int[] uiPixels()
		{
			BufferProvider bp = client.getBufferProvider();
			return bp == null ? null : bp.getPixels();
		}

		@Override
		public int scale()
		{
			return client.getScale();
		}

		@Override
		public int tick()
		{
			return client.getTickCount();
		}

		@Override
		public float[] playerLocal()
		{
			Player p = client.getLocalPlayer();
			if (p == null || p.getLocalLocation() == null)
			{
				return null;
			}
			return new float[]{p.getLocalLocation().getX(), p.getLocalLocation().getY()};
		}

		@Override
		public Object liveScene()
		{
			return client.getScene();
		}

		@Override
		public Object liveWorldView()
		{
			return client.getTopLevelWorldView();
		}

		@Override
		public int gameCycle()
		{
			return client.getGameCycle();
		}

		@Override
		public void onClientThread(Runnable r)
		{
			clientThread.invoke(r);
		}
	};

	private final Path dir = new File(RuneLite.RUNELITE_DIR, "render-replay").toPath();
	// Working files of the recording in progress; Stop copies them into the slot's folder.
	private final Path drawsFile = dir.resolve("recording.draws.jsonl");
	private final Path modelsFile = dir.resolve("recording.models.jsonl");

	private final Slot[] slots = new Slot[SLOT_COUNT];
	private volatile int selectedSlot;
	// The selected slot's recorder.
	private volatile DrawCallbacks recording;
	private SettingsFingerprint pendingSettings;
	private volatile State state = State.IDLE;
	private volatile String message = "Idle";
	private volatile String settingsWarning = "";
	private boolean resumeAfterDrag;
	private BufferedWriter drawOut;
	private BufferedWriter modelsOut;

	private RenderReplayPanel panel;
	private NavigationButton navButton;
	private PlaybackKeys playbackKeys;
	private ScheduledFuture<?> refresher;

	@Override
	protected void startUp() throws IOException
	{
		Files.createDirectories(dir);
		panel = new RenderReplayPanel(this::toggleRecord, this::togglePlay, this::seek, this::togglePause, d -> step(d, false));
		panel.setOnDragState(this::dragState);
		panel.setSlotHandlers(this::selectSlot, this::exportSlot, this::importClipboard);
		navButton = NavigationButton.builder().tooltip("Render Replay").icon(icon()).priority(9).panel(panel).build();
		clientToolbar.addNavigation(navButton);
		playbackKeys = new PlaybackKeys(this::togglePause, this::step);
		keyManager.registerKeyListener(playbackKeys);
		refresher = executor.scheduleWithFixedDelay(() -> clientThread.invoke(this::refreshPlayback), 1, 1, TimeUnit.SECONDS);
		publish("Idle");
	}

	@Override
	protected void shutDown()
	{
		refresher.cancel(false);
		keyManager.unregisterKeyListener(playbackKeys);
		clientToolbar.removeNavigation(navButton);
		clientThread.invoke(() ->
		{
			stopRecording();
			stopPlayback();
			Arrays.fill(slots, null);
			recording = null;
			DrawRecorder.KEEPER.clear();
		});
	}

	// ---- controls ----

	private void toggleRecord()
	{
		clientThread.invokeLater(() ->
		{
			if (state == State.RECORDING)
			{
				stopRecording();
			}
			else if (state == State.IDLE)
			{
				startRecording();
			}
		});
	}

	private void togglePlay()
	{
		clientThread.invokeLater(() ->
		{
			if (state == State.PLAYING)
			{
				stopPlayback();
			}
			else if (state == State.IDLE)
			{
				startPlayback(0);
			}
		});
	}

	// After a panel click, hand keyboard focus back to the game canvas so the playback keys keep working.
	private void focusGame()
	{
		Canvas canvas = client.getCanvas();
		if (canvas != null)
		{
			SwingUtilities.invokeLater(canvas::requestFocusInWindow);
		}
	}

	private void togglePause()
	{
		focusGame();
		clientThread.invokeLater(() ->
		{
			if (state != State.PLAYING || recording == null)
			{
				return;
			}
			if (DrawRecorder.isPaused(recording))
			{
				DrawRecorder.resume(recording);
				publish("Playing (world is uninteractive)");
			}
			else if (DrawRecorder.pause(recording))
			{
				publish("Paused at tick " + DrawRecorder.replayTick(recording));
			}
		});
	}

	/** Steps one game tick forward (dir > 0) or back, pausing first. repeat: a key auto-repeat, not a fresh press. */
	private void step(int dir, boolean repeat)
	{
		if (!repeat)
		{
			focusGame();
		}
		clientThread.invokeLater(() ->
		{
			if (state != State.PLAYING || recording == null)
			{
				return;
			}
			if (!DrawRecorder.isPaused(recording))
			{
				DrawRecorder.pause(recording);
			}
			DrawRecorder.stepTick(recording, dir, repeat);
			if (!repeat)
			{
				publish(dir > 0 ? "Playing one tick…" : "Paused at tick " + DrawRecorder.replayTick(recording));
			}
		});
	}

	/** Slider grabbed or released: freeze playback while dragging, and resume afterwards if it was running. */
	private void dragState(boolean dragging)
	{
		clientThread.invokeLater(() ->
		{
			if (recording == null || state != State.PLAYING)
			{
				resumeAfterDrag = false;
				return;
			}
			if (dragging)
			{
				resumeAfterDrag = !DrawRecorder.isPaused(recording);
				DrawRecorder.pause(recording);
			}
			else
			{
				if (resumeAfterDrag)
				{
					DrawRecorder.resume(recording);
				}
				resumeAfterDrag = false;
			}
		});
	}

	/** Slider released: seek a running playback, or start playback from that point when idle. */
	private void seek(long ms)
	{
		focusGame();
		clientThread.invokeLater(() ->
		{
			if (recording == null)
			{
				return;
			}
			long[] tl = DrawRecorder.timeline(recording);
			// never seek past the last tick
			long at = tl == null ? ms : Math.min(ms, Math.max(0, tl[1] - 600));
			if (state == State.PLAYING)
			{
				DrawRecorder.seek(recording, at);
			}
			else if (state == State.IDLE)
			{
				startPlayback(at);
			}
		});
	}

	// ---- playback (client thread) ----

	private void startPlayback(long offsetMs)
	{
		DrawCallbacks cur = client.getDrawCallbacks();
		if (recording == null || !DrawRecorder.hasRetained(recording))
		{
			publish("Nothing to play: record or import first");
			return;
		}
		if (cur == null || DrawRecorder.unwrap(recording) != DrawRecorder.unwrap(cur))
		{
			publish("Renderer changed since recording; record again");
			return;
		}
		if (client.getGameState() != GameState.LOGGED_IN)
		{
			publish("Log in first");
			return;
		}
		// Terrain is drawn from the scene the GPU plugin has loaded now. Unless the recorded scene can be swapped in,
		// refuse to show recorded objects over another area's terrain.
		int[] si = DrawRecorder.sceneInfo(recording, client.getScene());
		if (si != null && (si[0] != si[4] || si[1] != si[5]) && !DrawRecorder.canSwapScenes(recording))
		{
			float[] pp = DrawRecorder.firstPlayer(recording);
			String where = pp == null ? "" : " (near world tile " + (si[0] + ((int) pp[0] >> 7)) + ", " + (si[1] + ((int) pp[1] >> 7)) + ")";
			publish("Recorded in a different map area" + where + ". Go back there to play it back.");
			return;
		}
		settingsWarning = settingsMismatch();
		state = State.PLAYING;
		playbackKeys.setActive(true);
		DrawRecorder.startPlayback(recording, () -> clientThread.invokeLater(this::finishPlayback), -1, offsetMs);
		client.setDrawCallbacks(recording);
		publish("Playing (world is uninteractive)");
	}

	private void stopPlayback()
	{
		if (state == State.PLAYING)
		{
			DrawRecorder.stopPlayback(recording);
			finishPlayback();
		}
	}

	private void finishPlayback()
	{
		if (state != State.PLAYING)
		{
			return;
		}
		playbackKeys.setActive(false);
		DrawCallbacks cur = client.getDrawCallbacks();
		if (DrawRecorder.isWrapped(cur))
		{
			client.setDrawCallbacks(DrawRecorder.unwrap(cur));
		}
		state = State.IDLE;
		publish("Playback finished");
	}

	// ---- slots, export, import ----

	private Path slotDir(int i)
	{
		return dir.resolve("slots").resolve("slot" + (i + 1));
	}

	private String slotLabel(int i)
	{
		Slot sl = slots[i];
		if (sl == null || !DrawRecorder.hasRetained(sl.recorder))
		{
			return "Slot " + (i + 1) + " · empty";
		}
		long[] tl = DrawRecorder.timeline(sl.recorder);
		double ticks = tl == null ? 0 : tl[1] / 600.0;
		return String.format(Locale.ROOT, "Slot %d · %.1f ticks · %tR", i + 1, ticks, new Date(sl.createdMs));
	}

	private void refreshSlots()
	{
		List<String> labels = new ArrayList<>();
		for (int i = 0; i < SLOT_COUNT; i++)
		{
			labels.add(slotLabel(i));
		}
		Slot cur = slots[selectedSlot];
		panel.updateSlots(labels, selectedSlot, state == State.IDLE, cur != null && DrawRecorder.hasRetained(cur.recorder));
	}

	private void selectSlot(int i)
	{
		clientThread.invokeLater(() ->
		{
			if (state != State.IDLE || i < 0 || i >= SLOT_COUNT)
			{
				refreshSlots();
				return;
			}
			selectedSlot = i;
			recording = slots[i] == null ? null : slots[i].recorder;
			settingsWarning = "";
			publish(recording == null ? "Slot " + (i + 1) + " is empty: press Record to fill it" : "Slot " + (i + 1) + " selected");
		});
	}

	private void saveSlotFiles(int i)
	{
		try
		{
			Files.createDirectories(slotDir(i));
			for (Path f : new Path[]{drawsFile, modelsFile})
			{
				Files.copy(f, slotDir(i).resolve(f.getFileName().toString()), StandardCopyOption.REPLACE_EXISTING);
			}
		}
		catch (IOException e)
		{
			log.warn("could not keep slot files", e);
		}
	}

	private Map<String, Object> manifest(int i, Slot sl)
	{
		long[] tl = DrawRecorder.timeline(sl.recorder);
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("format", "rrec-1");
		m.put("created", Instant.ofEpochMilli(sl.createdMs).toString());
		m.put("slot", i + 1);
		m.put("retainedFrames", DrawRecorder.retainedFrames(sl.recorder));
		m.put("ticks", tl == null ? 0 : Math.round(tl[1] / 600.0 * 10) / 10.0);
		m.put("scale", sl.scale);
		m.put("canvas", new int[]{sl.canvasW, sl.canvasH});
		m.put("settingsHash", sl.settings.overall);
		m.put("settingsGroups", sl.settings.groups);
		return m;
	}

	private void writeZip(OutputStream os, int i, Slot sl) throws IOException
	{
		try (ZipOutputStream z = new ZipOutputStream(os))
		{
			z.putNextEntry(new ZipEntry("manifest.json"));
			z.write(gson.toJson(manifest(i, sl)).getBytes(StandardCharsets.UTF_8));
			z.closeEntry();
			z.putNextEntry(new ZipEntry("frames.bin"));
			DrawRecorder.exportFrames(sl.recorder, z);
			z.closeEntry();
			for (Path f : new Path[]{drawsFile, modelsFile})
			{
				Path kept = slotDir(i).resolve(f.getFileName().toString());
				if (Files.exists(kept))
				{
					z.putNextEntry(new ZipEntry(f.getFileName().toString()));
					Files.copy(kept, z);
					z.closeEntry();
				}
			}
		}
	}

	/** Puts the selected slot on the clipboard as text (base64 of a zip), and keeps a .rrec file copy. */
	private void exportSlot()
	{
		final int i = selectedSlot;
		final Slot sl = slots[i];
		if (sl == null || state != State.IDLE)
		{
			return;
		}
		publish("Copying slot " + (i + 1) + " to the clipboard…");
		executor.execute(() ->
		{
			try
			{
				ByteArrayOutputStream bos = new ByteArrayOutputStream();
				writeZip(bos, i, sl);
				Path exports = dir.resolve("exports");
				Files.createDirectories(exports);
				Path file = exports.resolve(String.format("slot%d-%tY%<tm%<td-%<tH%<tM%<tS.rrec", i + 1, new Date()));
				Files.write(file, bos.toByteArray());
				String text;
				String what;
				if (bos.size() * 4L / 3 + CLIP_PREFIX.length() <= CLIP_MAX_BYTES)
				{
					text = CLIP_PREFIX + Base64.getEncoder().encodeToString(bos.toByteArray());
					what = "Copied slot " + (i + 1) + " to the clipboard (" + (text.length() >> 10) + " KB). Paste it anywhere, or use Import.";
				}
				else
				{
					text = CLIP_FILE_PREFIX + file;
					what = "Slot " + (i + 1) + " is too big for the clipboard (" + (bos.size() >> 10) + " KB zipped). Saved "
						+ file.getFileName() + " and copied its path: Import reads it back.";
				}
				Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(text), null);
				publish(what);
			}
			catch (IOException | RuntimeException e)
			{
				log.warn("export failed", e);
				publish("Export failed: " + e.getMessage());
			}
		});
	}

	/** Reads a recording from the clipboard (the data itself, or the path of a .rrec file) into the selected slot. */
	private void importClipboard()
	{
		String text;
		try
		{
			Object clip = Toolkit.getDefaultToolkit().getSystemClipboard().getData(DataFlavor.stringFlavor);
			String raw = clip == null ? "" : clip.toString().trim();
			byte[] zip;
			if (raw.startsWith(CLIP_FILE_PREFIX))
			{
				Path f = Paths.get(raw.substring(CLIP_FILE_PREFIX.length()).trim());
				if (!f.getFileName().toString().endsWith(".rrec"))
				{
					throw new IOException("not a .rrec file");
				}
				zip = Files.readAllBytes(f);
			}
			else if (raw.startsWith(CLIP_PREFIX))
			{
				zip = Base64.getDecoder().decode(raw.substring(CLIP_PREFIX.length()));
			}
			else
			{
				throw new IOException("the clipboard does not hold a Render Replay recording (copy one with Export first)");
			}
			RecordingFile rf = RecordingFile.parse(zip);
			text = describeImport(rf) + "\n\nLoading it into slot " + (selectedSlot + 1) + "… (press Playback when the panel says it is loaded)";
			clientThread.invokeLater(() -> loadIntoSlot(rf, zip));
		}
		catch (IOException | RuntimeException | UnsupportedFlavorException ex)
		{
			text = "Could not import: " + ex.getMessage();
		}
		JOptionPane.showMessageDialog(panel, text, "Render Replay import", JOptionPane.INFORMATION_MESSAGE);
	}

	private static Map<String, String> settingsGroups(JsonObject manifest)
	{
		Map<String, String> groups = new TreeMap<>();
		if (manifest.has("settingsGroups"))
		{
			for (Map.Entry<String, JsonElement> e : manifest.getAsJsonObject("settingsGroups").entrySet())
			{
				groups.put(e.getKey(), e.getValue().getAsString());
			}
		}
		return groups;
	}

	private String describeImport(RecordingFile rf)
	{
		JsonObject m = rf.manifest;
		SettingsFingerprint cur = SettingsFingerprint.of(SettingsFingerprint.collect(configManager));
		List<String> diff = SettingsFingerprint.diff(settingsGroups(m), cur.groups);
		JsonArray cv = m.getAsJsonArray("canvas");
		return "Recording: " + m.get("ticks").getAsString() + " ticks, " + rf.frames.size() + " frames, recorded " + m.get("created").getAsString()
			+ "\nZoom " + m.get("scale").getAsInt() + ", canvas " + cv.get(0).getAsInt() + " x " + cv.get(1).getAsInt()
			+ "\n\n" + (diff.isEmpty() ? "Plugin settings: identical to now (hash " + m.get("settingsHash").getAsString() + ")."
			: "WARNING: plugin settings differ from when this was recorded (" + diff.size() + " plugin group(s): "
			+ String.join(", ", diff.subList(0, Math.min(8, diff.size()))) + (diff.size() > 8 ? ", …" : "")
			+ ").\nExpect missing or extra overlays and other errors.");
	}

	/** Keeps an imported recording's files with its slot, in place of an earlier recording's, so the slot can be exported again. */
	private void saveImportedFiles(int i, byte[] zip)
	{
		try
		{
			Files.createDirectories(slotDir(i));
			Set<String> names = new HashSet<>();
			for (Path f : new Path[]{drawsFile, modelsFile})
			{
				names.add(f.getFileName().toString());
				Files.deleteIfExists(slotDir(i).resolve(f.getFileName().toString()));
			}
			try (ZipInputStream z = new ZipInputStream(new ByteArrayInputStream(zip)))
			{
				for (ZipEntry e; (e = z.getNextEntry()) != null; )
				{
					if (names.contains(e.getName()))
					{
						Files.copy(z, slotDir(i).resolve(e.getName()), StandardCopyOption.REPLACE_EXISTING);
					}
				}
			}
		}
		catch (IOException e)
		{
			log.warn("could not keep the imported recording's files", e);
		}
	}

	/** Client thread: turns the parsed file into a playable recording in the selected slot. */
	private void loadIntoSlot(RecordingFile rf, byte[] zip)
	{
		if (state != State.IDLE)
		{
			publish("Import needs the client idle (stop recording or playback first)");
			return;
		}
		DrawCallbacks cur = client.getDrawCallbacks();
		if (cur == null || DrawRecorder.isWrapped(cur))
		{
			publish("Import: the renderer is not ready");
			return;
		}
		try
		{
			DrawCallbacks recorder = DrawRecorder.fromFile(cur, probe, rf);
			JsonArray cv = rf.manifest.getAsJsonArray("canvas");
			slots[selectedSlot] = new Slot(recorder,
				SettingsFingerprint.fromGroups(rf.manifest.get("settingsHash").getAsString(), settingsGroups(rf.manifest)),
				rf.manifest.get("scale").getAsInt(), cv.get(0).getAsInt(), cv.get(1).getAsInt());
			saveImportedFiles(selectedSlot, zip);
			recording = recorder;
			publish("Imported into slot " + (selectedSlot + 1) + " (" + rf.frames.size() + " frames). Press Playback.");
		}
		catch (RuntimeException e)
		{
			log.warn("import failed", e);
			publish("Import failed: " + e);
		}
	}

	/** Names the plugin groups whose settings changed since the selected slot was recorded, or "". */
	private String settingsMismatch()
	{
		Slot sl = slots[selectedSlot];
		if (sl == null)
		{
			return "";
		}
		try
		{
			List<String> diff = SettingsFingerprint.diff(sl.settings.groups, SettingsFingerprint.of(SettingsFingerprint.collect(configManager)).groups);
			if (diff.isEmpty())
			{
				return "";
			}
			return "<b>Plugin settings changed since recording</b> (" + String.join(", ", diff.subList(0, Math.min(3, diff.size())))
				+ (diff.size() > 3 ? " +" + (diff.size() - 3) + " more" : "") + "): expect errors.";
		}
		catch (RuntimeException e)
		{
			return "";
		}
	}

	// ---- recording (client thread) ----

	private void startRecording()
	{
		if (client.getDrawCallbacks() == null)
		{
			publish("Nothing to record: turn on the GPU plugin first");
			return;
		}
		try
		{
			drawOut = Files.newBufferedWriter(drawsFile, StandardCharsets.UTF_8);
			modelsOut = Files.newBufferedWriter(modelsFile, StandardCharsets.UTF_8);
		}
		catch (IOException e)
		{
			log.warn("could not open recording files", e);
			publish("Cannot write recording: " + e.getMessage());
			return;
		}
		// The other slots keep their memory; this recording gets what is left of the shared budget.
		long others = 0;
		for (int i = 0; i < SLOT_COUNT; i++)
		{
			if (i != selectedSlot && slots[i] != null)
			{
				others += DrawRecorder.retainedBytes(slots[i].recorder);
			}
		}
		DrawRecorder.budgetBytes = Math.max(TOTAL_BUDGET / SLOT_COUNT, TOTAL_BUDGET - others);
		try
		{
			pendingSettings = SettingsFingerprint.of(SettingsFingerprint.collect(configManager));
		}
		catch (RuntimeException e)
		{
			log.warn("could not read plugin settings for the fingerprint", e);
			pendingSettings = SettingsFingerprint.of(new HashMap<>());
		}
		try
		{
			// The current scene loaded before the recorder was installed: copy it now, so its frames can be replayed
			// after a map change.
			Scene live = client.getScene();
			if (live != null && DrawRecorder.KEEPER.get(live) == null)
			{
				DrawRecorder.KEEPER.capture(live);
			}
		}
		catch (RuntimeException e)
		{
			log.warn("could not copy the current scene", e);
		}
		state = State.RECORDING;
		ensureWrapped();
		publish("Recording…");
	}

	private void stopRecording()
	{
		if (state != State.RECORDING)
		{
			return;
		}
		state = State.IDLE;
		int frames = 0;
		if (recording != null)
		{
			DrawRecorder.stopCapture(recording);
			frames = DrawRecorder.retainedFrames(recording);
		}
		DrawCallbacks cur = client.getDrawCallbacks();
		if (DrawRecorder.isWrapped(cur))
		{
			client.setDrawCallbacks(DrawRecorder.unwrap(cur));
		}
		if (recording != null)
		{
			DrawRecorder.drainWrites(recording);
		}
		try
		{
			drawOut.close();
			modelsOut.close();
		}
		catch (IOException e)
		{
			log.warn("close failed", e);
		}
		saveSlotFiles(selectedSlot);
		publish("Saved " + frames + " frames in slot " + (selectedSlot + 1));
	}

	@Subscribe
	public void onBeforeRender(BeforeRender e)
	{
		// The GPU plugin replaces the callbacks whenever it restarts.
		if (state == State.RECORDING && client.getGameState() == GameState.LOGGED_IN)
		{
			ensureWrapped();
		}
	}

	private void ensureWrapped()
	{
		DrawCallbacks cur = client.getDrawCallbacks();
		if (cur == null || DrawRecorder.isWrapped(cur))
		{
			return;
		}
		recording = DrawRecorder.wrap(cur, gson, drawOut, modelsOut, FileReplay::copyOf, probe);
		slots[selectedSlot] = new Slot(recording, pendingSettings, client.getScale(), client.getCanvasWidth(), client.getCanvasHeight());
		client.setDrawCallbacks(recording);
	}

	// ---- panel ----

	private void publish(String msg)
	{
		message = msg;
		panel.update(state, recording != null && DrawRecorder.hasRetained(recording), msg);
		refreshSlots();
		clientThread.invoke(this::refreshPlayback);
	}

	/** Client thread, on every state change and once a second: the timeline, and what is off about a running playback. */
	private void refreshPlayback()
	{
		DrawCallbacks rec = recording;
		if (rec == null || state == State.RECORDING)
		{
			return;
		}
		long[] tl = DrawRecorder.timeline(rec);
		if (tl != null)
		{
			panel.updateTimeline(tl[0], tl[1], true);
		}
		panel.setDragSeek(state == State.PLAYING);
		panel.updatePlayback(state == State.PLAYING, DrawRecorder.isPaused(rec), DrawRecorder.replayTick(rec));
		if (state != State.PLAYING)
		{
			return;
		}
		int[] si = DrawRecorder.sceneInfo(rec, client.getScene());
		boolean areaDiffers = si != null && (si[0] != si[4] || si[1] != si[5]);
		int recorded = DrawRecorder.replayScale(rec), live = client.getScale();
		String m = DrawRecorder.uiSizeMismatch(rec) ? "Playing. The window size changed since recording, so the recorded UI/minimap cannot be shown. Resize back to the recorded size."
			: areaDiffers && DrawRecorder.canSwapScenes(rec) ? "Playing in another map area: the recorded scene is reloaded, yours is restored afterwards."
			: areaDiffers ? "Playing. You are in a different area than the recording: terrain shown is your CURRENT area (recorded objects only)."
			: recorded > 0 && Math.abs(recorded - live) > recorded * 0.02 ? "Playing. Zoom differs: recorded " + recorded + ", yours " + live + ". Match your zoom for an exact picture."
			: DrawRecorder.isPaused(rec) ? "Paused at tick " + DrawRecorder.replayTick(rec) + " (world is uninteractive)"
			: "Playing (world is uninteractive)";
		if (!settingsWarning.isEmpty())
		{
			m = settingsWarning + "<br>" + m;
		}
		if (!m.equals(message))
		{
			message = m;
			panel.update(state, true, m);
		}
	}

	private static BufferedImage icon()
	{
		BufferedImage img = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
		Graphics2D g = img.createGraphics();
		g.setColor(new Color(0xd32f2f));
		g.fillOval(2, 2, 12, 12);
		g.dispose();
		return img;
	}
}
