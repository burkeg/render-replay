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
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
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
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.input.KeyManager;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.ClientToolbar;
import net.runelite.client.ui.NavigationButton;
import net.runelite.client.util.Filepath;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.widgets.Widget;

/**
 * Records the game viewport's renderer input into one of four in-memory slots, and plays a slot back by issuing the
 * recorded calls in place of the live ones. Everything stays in local files; nothing is sent anywhere.
 */
@Slf4j
@PluginDescriptor(
	name = "Render Replay",
	internalName = "render-replay",
	description = "Record what the client sends to the GPU renderer and replay it in the game viewport",
	tags = {"replay", "record", "gpu"}
)
public class RenderReplayPlugin extends Plugin
{
	enum State
	{
		IDLE, RECORDING, SAVING, PLAYING
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
		public int uiWidth()
		{
			BufferProvider bp = client.getBufferProvider();
			return bp == null ? 0 : bp.getWidth();
		}

		@Override
		public int[] minimap()
		{
			for (int id : MINIMAP_WIDGETS)
			{
				Widget w = client.getWidget(id);
				if (w != null && !w.isHidden())
				{
					java.awt.Rectangle b = w.getBounds();
					if (b != null && b.width > 20 && b.height > 20)
					{
						return new int[]{b.x + b.width / 2, b.y + b.height / 2, Math.min(b.width, b.height) / 2};
					}
				}
			}
			return null;
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

	// A recording's two files: the draw calls of every frame, and the geometry they refer to.
	private static final String[] RECORDING_FILES = {"recording.draws.jsonl", "recording.models.jsonl"};

	// The plugin's own directory. The working files of the recording in progress are written straight into it, and Stop
	// copies them into the slot's folder.
	private Filepath dir;

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
		dir = getPluginDirectory();
		dir.createDirectories();
		panel = new RenderReplayPanel(this::toggleRecord, this::togglePlay, this::seek, this::togglePause, d -> step(d, false));
		panel.setOnDragState(this::dragState);
		panel.setSlotHandlers(this::selectSlot, this::exportSlot, this::importRecording);
		int q = savedQuality();
		DrawRecorder.preset = Presets.ALL[q];
		panel.setQuality(q);
		panel.setOnQuality(this::chooseQuality);
		refreshProjection();
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
			stopRecordingNow();
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
				publish("Playing");
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
		publish("Playing");
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

	private Filepath slotDir(int i)
	{
		return dir.join("slots", "slot" + (i + 1));
	}

	private String slotLabel(int i)
	{
		Slot sl = slots[i];
		if (sl == null || !DrawRecorder.hasRetained(sl.recorder))
		{
			return "Slot " + (i + 1) + " · empty";
		}
		long[] tl = DrawRecorder.timeline(sl.recorder);
		double secs = tl == null ? 0 : tl[1] / 1000.0;
		String length = secs < 60 ? String.format(Locale.ROOT, "%.1f s", secs) : clock((long) (secs * 1000));
		return "Slot " + (i + 1) + " · " + length + " · " + size(DrawRecorder.retainedBytes(sl.recorder));
	}

	private static String size(long bytes)
	{
		return bytes < 1 << 20 ? Math.max(1, bytes >> 10) + " KB" : String.format(Locale.ROOT, "%.1f MB", bytes / (double) (1 << 20));
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
			slotDir(i).createDirectories();
			for (String name : RECORDING_FILES)
			{
				Filepath kept = slotDir(i).joinSegment(name);
				kept.deleteIfExists();
				// the files are closed and the next recording starts new ones: moving is a rename, where a copy of
				// hundreds of MB took seconds
				Filepath made = dir.joinSegment(name);
				if (made.exists())
				{
					made.moveTo(kept);
				}
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
			for (String name : RECORDING_FILES)
			{
				Filepath kept = slotDir(i).joinSegment(name);
				if (kept.exists())
				{
					z.putNextEntry(new ZipEntry(name));
					try (InputStream in = kept.openInputStream())
					{
						in.transferTo(z);
					}
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
				Filepath exports = dir.joinSegment("exports");
				exports.createDirectories();
				Filepath file = exports.joinSegment(String.format("slot%d-%tY%<tm%<td-%<tH%<tM%<tS.rrec", i + 1, new Date()));
				file.write(bos.toByteArray());
				String text;
				String what;
				if (bos.size() * 4L / 3 + CLIP_PREFIX.length() <= CLIP_MAX_BYTES)
				{
					text = CLIP_PREFIX + Base64.getEncoder().encodeToString(bos.toByteArray());
					what = "Copied slot " + (i + 1) + " to the clipboard (" + (text.length() >> 10) + " KB). Paste it anywhere, or use Import.";
				}
				else
				{
					text = CLIP_FILE_PREFIX + file.getFileName();
					what = "Slot " + (i + 1) + " is too big for the clipboard (" + (bos.size() >> 10) + " KB zipped). Saved "
						+ file.getFileName() + " in the plugin's exports folder and copied its name: Import reads it back.";
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

	/** Import button: loads a recording into the selected slot. */
	private void importRecording()
	{
		String text;
		try
		{
			final byte[] zip = importBytes();
			if (zip == null)
			{
				return;
			}
			RecordingFile rf = RecordingFile.parse(zip);
			text = describeImport(rf) + "\n\nLoading it into slot " + (selectedSlot + 1) + "… (press Playback when the panel says it is loaded)";
			clientThread.invokeLater(() -> loadIntoSlot(rf, zip));
		}
		catch (IOException | RuntimeException ex)
		{
			text = "Could not import: " + ex.getMessage();
		}
		JOptionPane.showMessageDialog(panel, text, "Render Replay import", JOptionPane.INFORMATION_MESSAGE);
	}

	/**
	 * The recording to import: the one on the clipboard, the exported file the clipboard names, or else a .rrec file the
	 * player picks. Null when they cancel.
	 */
	private byte[] importBytes() throws IOException
	{
		String raw = clipboardText();
		if (raw.startsWith(CLIP_PREFIX))
		{
			return Base64.getDecoder().decode(raw.substring(CLIP_PREFIX.length()));
		}
		Filepath exports = dir.joinSegment("exports");
		exports.createDirectories();
		Filepath file;
		if (raw.startsWith(CLIP_FILE_PREFIX))
		{
			file = exports.joinSegment(raw.substring(CLIP_FILE_PREFIX.length()).trim());
		}
		else
		{
			List<Filepath> picked = new Filepath.Chooser()
				.setIsOpen()
				.setAcceptsFiles()
				.setDialogTitle("Import a Render Replay recording")
				.addExtensionFilter("Render Replay recording (.rrec)", "rrec")
				.setCurrentDirectory(exports)
				.showDialog(panel);
			if (picked == null || picked.isEmpty())
			{
				return null;
			}
			file = picked.get(0);
		}
		if (!file.getFileName().endsWith(".rrec"))
		{
			throw new IOException("not a .rrec file");
		}
		try (InputStream in = file.openInputStream())
		{
			return in.readAllBytes();
		}
	}

	private static String clipboardText()
	{
		try
		{
			Object clip = Toolkit.getDefaultToolkit().getSystemClipboard().getData(DataFlavor.stringFlavor);
			return clip == null ? "" : clip.toString().trim();
		}
		catch (IOException | RuntimeException | UnsupportedFlavorException e)
		{
			return "";
		}
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
			slotDir(i).createDirectories();
			Set<String> names = new HashSet<>(Arrays.asList(RECORDING_FILES));
			for (String name : RECORDING_FILES)
			{
				slotDir(i).joinSegment(name).deleteIfExists();
			}
			try (ZipInputStream z = new ZipInputStream(new ByteArrayInputStream(zip)))
			{
				for (ZipEntry e; (e = z.getNextEntry()) != null; )
				{
					if (names.contains(e.getName()))
					{
						try (OutputStream out = slotDir(i).joinSegment(e.getName()).openOutputStream())
						{
							z.transferTo(out);
						}
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
			drawOut = dir.joinSegment(RECORDING_FILES[0]).openBufferedWriter();
			modelsOut = dir.joinSegment(RECORDING_FILES[1]).openBufferedWriter();
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
		fullNoticed = false;
		panel.setStopLabel(null, null);
		ensureWrapped();
		publish("Recording…");
	}

	/**
	 * Stop pressed: capturing ends at once and the game keeps drawing, while the writer thread codes the frames still
	 * queued and saves the files. The panel shows a saving bar until it is done; Record and Playback wait for it.
	 */
	private void stopRecording()
	{
		if (state != State.RECORDING)
		{
			return;
		}
		DrawCallbacks rec = recording;
		if (rec == null)
		{
			stopRecordingNow();
			return;
		}
		state = State.SAVING;
		final int slot = selectedSlot;
		long fullAt = DrawRecorder.fullSince(rec);
		final String lost = fullAt < 0 ? "" : "; " + clock(System.currentTimeMillis() - fullAt) + " after memory filled was not recorded";
		final BufferedWriter draws = drawOut, models = modelsOut;
		Runnable done = () ->
		{
			// writer thread, after every queued frame
			try
			{
				closeQuietly(draws);
				closeQuietly(models);
				saveSlotFiles(slot);
			}
			finally
			{
				clientThread.invokeLater(() ->
				{
					if (state == State.SAVING)
					{
						state = State.IDLE;
					}
					publish("Saved " + DrawRecorder.retainedFrames(rec) + " frames in slot " + (slot + 1) + lost);
				});
			}
		};
		boolean queued = DrawRecorder.finishCapture(rec, done);
		DrawCallbacks cur = client.getDrawCallbacks();
		if (DrawRecorder.isWrapped(cur))
		{
			client.setDrawCallbacks(DrawRecorder.unwrap(cur));
		}
		if (!queued)
		{
			// the writer's queue is full: hand it the save step as soon as there is room, behind every frame
			clientThread.invokeLater(() -> DrawRecorder.finishCapture(rec, done));
		}
		panel.showSaving(() -> DrawRecorder.savingProgress(rec));
		publish("Saving…");
	}

	private static void closeQuietly(BufferedWriter w)
	{
		try
		{
			if (w != null)
			{
				w.close();
			}
		}
		catch (IOException e)
		{
			log.warn("close failed", e);
		}
	}

	/** Stops and saves before returning (plugin shutdown). */
	private void stopRecordingNow()
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

	// ---- recording quality ----

	private static final String CONFIG_GROUP = "renderreplay";
	// The minimap in the fixed, resizable and resizable-modern layouts.
	private static final int[] MINIMAP_WIDGETS = {InterfaceID.ToplevelOsrsStretch.MINIMAP, InterfaceID.ToplevelPreEoc.MINIMAP,
		InterfaceID.Toplevel.MINIMAP};

	private int savedQuality()
	{
		try
		{
			String v = configManager.getConfiguration(CONFIG_GROUP, "quality");
			int i = v == null ? 0 : Integer.parseInt(v);
			return Math.max(0, Math.min(Presets.ALL.length - 1, i));
		}
		catch (NumberFormatException e)
		{
			return 0;
		}
	}

	private void chooseQuality(int i)
	{
		configManager.setConfiguration(CONFIG_GROUP, "quality", i);
		DrawRecorder.preset = Presets.ALL[i];
		refreshProjection();
	}

	/**
	 * The box beside the quality slider. While recording: the time left before this slot's memory is full, from the
	 * rate the recording grows at (after that the oldest frames are dropped as it goes). Otherwise: the max recording
	 * time at the chosen quality, from the steady rate of this slot's recording when it has run long enough (scaled to
	 * the chosen quality), else from the sample the presets were measured on.
	 */
	private void refreshProjection()
	{
		Presets.Preset p = DrawRecorder.preset;
		DrawCallbacks rec = recording;
		if (state == State.RECORDING && rec != null)
		{
			long budget = DrawRecorder.budgetBytes, used = DrawRecorder.retainedBytes(rec);
			long fullAt = DrawRecorder.fullSince(rec);
			if (fullAt >= 0)
			{
				// recording stopped at the limit; saving waits for the user, who sees how much play went unrecorded
				long[] tl = DrawRecorder.timeline(rec);
				String held = tl == null ? "what was recorded" : "the " + duration(tl[1] / 1000.0) + " recorded";
				panel.setProjected("Full", "<html>Recording stopped when the " + (budget >> 20) + " MB this slot can use was full.<br>"
					+ "It holds " + held + ".</html>");
				panel.setStopLabel("Save · " + clock(System.currentTimeMillis() - fullAt) + " lost (memory full)",
					"<html>Recording stopped when this slot's memory was full; the time since then was not recorded.<br>"
						+ "Press to save " + held + ".</html>");
				if (!fullNoticed)
				{
					fullNoticed = true;
					publish("Memory full: recording stopped. Press Save to keep it.");
				}
				return;
			}
			panel.setStopLabel(null, null);
			Presets.Preset rp = DrawRecorder.preset(rec) != null ? DrawRecorder.preset(rec) : p;
			double rate = DrawRecorder.steadyRate(rec);
			double perSec = rate > 0 ? rate : rp.labBytesPerFrame * 55.0;
			String how = rate > 0 ? "Measured on this recording." : "Estimated from a sample recording until this one has run 10 seconds.";
			panel.setProjected(duration((budget - used) / Math.max(1.0, perSec)), "<html>Recording time left before the "
				+ (budget >> 20) + " MB this slot can use is full.<br>After that the oldest frames are dropped as you record.<br>"
				+ how + "</html>");
			return;
		}
		long others = 0;
		for (int i = 0; i < SLOT_COUNT; i++)
		{
			if (i != selectedSlot && slots[i] != null)
			{
				others += DrawRecorder.retainedBytes(slots[i].recorder);
			}
		}
		long budget = Math.max(TOTAL_BUDGET / SLOT_COUNT, TOTAL_BUDGET - others);
		Presets.Preset measured = rec == null ? null : DrawRecorder.preset(rec);
		double rate = rec == null ? 0 : DrawRecorder.steadyRate(rec);
		double perSec;
		String how;
		if (rate > 0 && measured != null)
		{
			perSec = rate * p.labBytesPerFrame / Math.max(1.0, measured.labBytesPerFrame);
			how = measured == p ? "Measured on this slot's recording." : "Scaled from this slot's recording, made at another quality.";
		}
		else
		{
			perSec = p.labBytesPerFrame * 55.0;
			how = "Estimated from a sample recording. Record for 20 seconds to measure yours.";
		}
		panel.setProjected(duration(budget / Math.max(1.0, perSec)), "<html>Max recording time with the " + (budget >> 20)
			+ " MB this slot can use.<br>" + how + "</html>");
	}

	private boolean fullNoticed;

	/** Elapsed time as m:ss, or h:mm:ss from an hour. */
	private static String clock(long ms)
	{
		long s = Math.max(0, ms / 1000);
		return s >= 3600 ? String.format("%d:%02d:%02d", s / 3600, s / 60 % 60, s % 60) : String.format("%d:%02d", s / 60, s % 60);
	}

	private static String duration(double secs)
	{
		if (secs < 60)
		{
			return Math.max(0, (int) secs) + " s";
		}
		if (secs < 7200)
		{
			return (int) (secs / 60) + " min";
		}
		return String.format("%.1f h", secs / 3600);
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
		refreshProjection();
		DrawCallbacks rec = recording;
		if (rec == null || state == State.RECORDING || state == State.SAVING)
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
			: DrawRecorder.isPaused(rec) ? "Paused at tick " + DrawRecorder.replayTick(rec)
			: "Playing";
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
