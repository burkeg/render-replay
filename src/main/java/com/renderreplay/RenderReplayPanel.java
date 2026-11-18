package com.renderreplay;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.util.function.IntConsumer;
import java.util.function.LongConsumer;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JSlider;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.border.EmptyBorder;
import net.runelite.client.ui.PluginPanel;

/**
 * Side panel: Record/Stop, Playback, a transport row (step back / pause / step forward), a timeline slider and a status
 * area. The layout is STATIC: every row has a fixed height and the status text lives in a fixed-size box, so nothing
 * moves when the text changes. Buttons and the slider are not focusable, so the arrow keys and Space stay with the game
 * canvas (they drive playback while it runs).
 */
class RenderReplayPanel extends PluginPanel
{
	private final JButton record = new JButton("Record");
	private final JButton play = new JButton("Playback");
	private final JButton stepBack = new JButton("< Tick");
	private final JButton pause = new JButton("Pause");
	private final JButton stepForward = new JButton("Tick >");
	private final JSlider timeline = new JSlider(0, 1, 0);
	private final JLabel time = new JLabel(timeText(0, 0, -1), SwingConstants.LEFT);
	private final javax.swing.JComboBox<String> slots = new javax.swing.JComboBox<>();
	private final JButton export = new JButton("Export");
	private final JButton importBtn = new JButton("Import");
	private boolean slotsProgrammatic;
	private volatile java.util.function.IntConsumer onSlot = i -> { };
	private volatile Runnable onExport = () -> { };
	private volatile Runnable onImport = () -> { };
	private final JLabel status = new JLabel("Idle");
	// Recording quality: one stop per Pareto-optimal preset, with the projected max recording time beside it.
	private final JSlider quality = new JSlider(0, Presets.ALL.length - 1, 0);
	private final JLabel projected = new JLabel("-", SwingConstants.CENTER);
	private final JLabel qualityName = new JLabel("", SwingConstants.LEFT);
	private java.util.function.IntConsumer onQuality = i -> {};
	private boolean qualityProgrammatic;
	// While a stopped recording is saved, a progress bar takes the Record button's place (the layout does not move).
	private final JPanel recordRow = new JPanel(new java.awt.CardLayout());
	private final javax.swing.JProgressBar saving = new javax.swing.JProgressBar(0, 1000);
	private javax.swing.Timer savingTimer;
	// While recording: the button's text and tooltip when it is not plain "Stop recording" (memory full: Save)
	private String stopLabel, stopTip;
	private RenderReplayPlugin.State shownState = RenderReplayPlugin.State.IDLE;
	private final JLabel hint = new JLabel("<html>Playing: Left/Right = 1 tick, Space = pause</html>");
	private int lastDragTick = -1;
	private boolean dragging;
	private volatile java.util.function.Consumer<Boolean> onDragState = b -> { };

	void setOnQuality(java.util.function.IntConsumer c)
	{
		onQuality = c;
	}

	/** Shows preset i as chosen, without telling the plugin. */
	void setQuality(int i)
	{
		qualityProgrammatic = true;
		quality.setValue(i);
		qualityProgrammatic = false;
		describeQuality(i);
	}

	/** The projected max recording time for the chosen preset, and how it was worked out. */
	void setProjected(String text, String how)
	{
		SwingUtilities.invokeLater(() ->
		{
			projected.setText(text);
			projected.setToolTipText(how);
		});
	}

	/** Memory per frame against lossless, in dB: 0 is lossless, every -10 dB keeps 10 times less. */
	static double compressionDb(Presets.Preset p)
	{
		return 10 * Math.log10(p.labBytesPerFrame / (double) Presets.ALL[0].labBytesPerFrame);
	}

	private void describeQuality(int i)
	{
		Presets.Preset p = Presets.ALL[i];
		double db = compressionDb(p);
		String q = i == 0 ? "0 dB, lossless" : String.format("%.1f dB", db);
		qualityName.setText("<html>Quality " + (i + 1) + " of " + Presets.ALL.length + ": " + q + "</html>");
		qualityName.setToolTipText("<html>" + p.name + "<br>Memory per frame against lossless: 0 dB keeps everything, "
			+ "every -10 dB keeps 10 times less" + (i == 0 ? "." : String.format(" (this one about %.0f times less).", Math.pow(10, -db / 10)))
			+ "<br>Applies to the next recording.</html>");
		quality.setToolTipText(qualityName.getToolTipText());
	}

	void setSlotHandlers(java.util.function.IntConsumer select, Runnable exportAction, Runnable importAction)
	{
		onSlot = select;
		onExport = exportAction;
		onImport = importAction;
	}

	/** Slot chooser: labels like "Slot 1 · 20.0 ticks"; only usable while idle so a recording/playback cannot be switched away. */
	void updateSlots(java.util.List<String> labels, int selected, boolean usable, boolean canExport)
	{
		SwingUtilities.invokeLater(() ->
		{
			slotsProgrammatic = true;
			try
			{
				boolean same = slots.getItemCount() == labels.size();
				for (int i = 0; same && i < labels.size(); i++)
				{
					same = labels.get(i).equals(slots.getItemAt(i));
				}
				if (!same)
				{
					slots.removeAllItems();
					for (String l : labels)
					{
						slots.addItem(l);
					}
				}
				if (slots.getSelectedIndex() != selected)
				{
					slots.setSelectedIndex(selected);
				}
			}
			finally
			{
				slotsProgrammatic = false;
			}
			slots.setEnabled(usable);
			export.setEnabled(usable && canExport);
			importBtn.setEnabled(usable);
		});
	}

	void setOnDragState(java.util.function.Consumer<Boolean> c)
	{
		onDragState = c;
	}

	private volatile boolean dragSeekEnabled; // only while a playback is running; an idle drag waits for release
	private boolean programmatic; // true while the plugin (not the user) moves the slider
	private int tickNumber = -1;

	RenderReplayPanel(Runnable onRecordToggle, Runnable onPlay, LongConsumer onSeek, Runnable onPause, IntConsumer onStep)
	{
		setBorder(new EmptyBorder(10, 10, 10, 10));
		setLayout(new GridBagLayout());

		for (JButton b : new JButton[]{record, play, stepBack, pause, stepForward})
		{
			b.setFocusable(false);
			b.setMargin(new Insets(2, 2, 2, 2));
		}
		timeline.setFocusable(false);
		stepBack.setToolTipText("Step back one game tick (Left arrow)");
		stepForward.setToolTipText("Play forward one game tick, then pause (Right arrow)");
		pause.setToolTipText("Pause / resume (Space)");

		record.addActionListener(e -> onRecordToggle.run());
		play.addActionListener(e -> onPlay.run());
		stepBack.addActionListener(e -> onStep.accept(-1));
		stepForward.addActionListener(e -> onStep.accept(1));
		pause.addActionListener(e -> onPause.run());
		stepBack.setEnabled(false);
		pause.setEnabled(false);
		stepForward.setEnabled(false);
		timeline.setEnabled(false);
		timeline.addChangeListener(e ->
		{
			if (programmatic)
			{
				return;
			}
			time.setText(timeText(timeline.getValue(), timeline.getMaximum(), tickNumber));
			if (!timeline.getValueIsAdjusting())
			{
				lastDragTick = -1;
				onSeek.accept(timeline.getValue()); // released or clicked on the track: seek / start from here
				if (dragging)
				{
					dragging = false;
					onDragState.accept(false); // after the seek: plugin resumes if it was running before the drag
				}
			}
			else if (dragSeekEnabled)
			{
				if (!dragging)
				{
					dragging = true;
					onDragState.accept(true); // plugin freezes playback on the frame so it cannot run on or finish mid-drag
				}
				// mid-drag: follow the thumb, but only once per tick boundary crossed (never per pixel)
				int tk = dragTick(timeline.getValue());
				if (tk != lastDragTick)
				{
					lastDragTick = tk;
					onSeek.accept(tk * 600L);
				}
			}
		});

		status.setVerticalAlignment(SwingConstants.TOP);
		hint.setVerticalAlignment(SwingConstants.TOP);
		hint.setForeground(Color.GRAY);
		hint.setFont(hint.getFont().deriveFont(hint.getFont().getSize2D() - 1f));

		JPanel transport = new JPanel(new GridBagLayout());
		transport.setOpaque(false);
		GridBagConstraints t = new GridBagConstraints();
		t.fill = GridBagConstraints.BOTH;
		t.weighty = 1;
		t.gridy = 0;
		t.insets = new Insets(0, 0, 0, 4);
		t.gridx = 0;
		t.weightx = 1.0;
		transport.add(fixed(stepBack, 32), t);
		t.gridx = 1;
		t.weightx = 1.15;
		transport.add(fixed(pause, 32), t);
		t.gridx = 2;
		t.weightx = 1.0;
		t.insets = new Insets(0, 0, 0, 0);
		transport.add(fixed(stepForward, 32), t);

		GridBagConstraints c = new GridBagConstraints();
		c.gridx = 0;
		c.weightx = 1;
		c.fill = GridBagConstraints.HORIZONTAL;
		c.anchor = GridBagConstraints.NORTH;
		c.insets = new Insets(0, 0, 8, 0);
		int row = 0;
		slots.setFocusable(false);
		slots.addActionListener(e ->
		{
			if (!slotsProgrammatic && slots.getSelectedIndex() >= 0)
			{
				onSlot.accept(slots.getSelectedIndex());
			}
		});
		export.setFocusable(false);
		importBtn.setFocusable(false);
		export.setMargin(new Insets(2, 2, 2, 2));
		importBtn.setMargin(new Insets(2, 2, 2, 2));
		export.setToolTipText("Copy this slot to the clipboard");
		importBtn.setToolTipText("Load a recording from the clipboard, or pick a .rrec file");
		export.addActionListener(e -> onExport.run());
		importBtn.addActionListener(e -> onImport.run());
		JPanel files = new JPanel(new GridBagLayout());
		files.setOpaque(false);
		GridBagConstraints fc = new GridBagConstraints();
		fc.fill = GridBagConstraints.BOTH;
		fc.weighty = 1;
		fc.weightx = 1;
		fc.gridx = 0;
		fc.insets = new Insets(0, 0, 0, 4);
		files.add(fixed(export, 28), fc);
		fc.gridx = 1;
		fc.insets = new Insets(0, 0, 0, 0);
		files.add(fixed(importBtn, 28), fc);
		quality.setSnapToTicks(true);
		quality.setMajorTickSpacing(1);
		quality.setPaintTicks(true);
		quality.setFocusable(false);
		quality.addChangeListener(e ->
		{
			describeQuality(quality.getValue());
			if (!qualityProgrammatic)
			{
				onQuality.accept(quality.getValue());
			}
		});
		projected.setOpaque(true);
		projected.setBorder(javax.swing.BorderFactory.createLineBorder(Color.GRAY));
		projected.setPreferredSize(new Dimension(62, 26));
		projected.setFont(projected.getFont().deriveFont(java.awt.Font.BOLD));
		qualityName.setForeground(Color.GRAY);
		qualityName.setFont(qualityName.getFont().deriveFont(qualityName.getFont().getSize2D() - 1f));
		JPanel qualityRow = new JPanel(new java.awt.BorderLayout(6, 0));
		qualityRow.setOpaque(false);
		qualityRow.add(quality, java.awt.BorderLayout.CENTER);
		qualityRow.add(projected, java.awt.BorderLayout.EAST);
		describeQuality(0);
		saving.setStringPainted(true);
		saving.setString("Saving…");
		recordRow.setOpaque(false);
		recordRow.add(record, "button");
		recordRow.add(saving, "saving");
		for (Object[] r : new Object[][]{{slots, 28}, {files, 28}, {qualityRow, 30}, {qualityName, 18}, {recordRow, 32}, {play, 32}, {transport, 32}, {timeline, 26}, {time, 34}, {status, 78}, {hint, 30}})
		{
			c.gridy = row++;
			add(fixed((JComponent) r[0], (Integer) r[1]), c);
		}
		c.gridy = row;
		c.weighty = 1;
		c.fill = GridBagConstraints.BOTH;
		add(new JPanel(), c); // filler pushes everything to the top; nothing below can move what is above
	}

	/** Fixed height, flexible width: this is what keeps the layout from shifting. */
	private static <T extends JComponent> T fixed(T comp, int height)
	{
		comp.setPreferredSize(new Dimension(10, height));
		comp.setMinimumSize(new Dimension(0, height));
		comp.setMaximumSize(new Dimension(Short.MAX_VALUE, height));
		return comp;
	}

	/** Shows the saving bar in place of the Record button, following progress (0..1, or -1 when done) until update() leaves SAVING. */
	void showSaving(java.util.function.DoubleSupplier progress)
	{
		SwingUtilities.invokeLater(() ->
		{
			if (savingTimer != null)
			{
				savingTimer.stop();
			}
			saving.setValue(0);
			saving.setString("Saving…");
			((java.awt.CardLayout) recordRow.getLayout()).show(recordRow, "saving");
			savingTimer = new javax.swing.Timer(100, e ->
			{
				double p = progress.getAsDouble();
				if (p < 0)
				{
					return;
				}
				saving.setValue((int) Math.round(p * 1000));
				saving.setString(p >= 1 ? "Saving files…" : String.format("Saving… %d%%", (int) (p * 100)));
			});
			savingTimer.start();
		});
	}

	/** While recording, what the Record button says (null for "Stop recording"), with its tooltip. */
	void setStopLabel(String text, String tip)
	{
		SwingUtilities.invokeLater(() ->
		{
			stopLabel = text;
			stopTip = tip;
			if (shownState == RenderReplayPlugin.State.RECORDING)
			{
				record.setText(text != null ? text : "Stop recording");
				record.setToolTipText(tip);
			}
		});
	}

	void update(RenderReplayPlugin.State state, boolean hasRecording, String message)
	{
		SwingUtilities.invokeLater(() ->
		{
			shownState = state;
			if (state != RenderReplayPlugin.State.SAVING)
			{
				if (savingTimer != null)
				{
					savingTimer.stop();
					savingTimer = null;
				}
				((java.awt.CardLayout) recordRow.getLayout()).show(recordRow, "button");
			}
			boolean rec = state == RenderReplayPlugin.State.RECORDING;
			record.setText(rec ? (stopLabel != null ? stopLabel : "Stop recording") : "Record");
			record.setToolTipText(rec ? stopTip : null);
			record.setEnabled(state != RenderReplayPlugin.State.PLAYING);
			play.setText(state == RenderReplayPlugin.State.PLAYING ? "Stop playback" : "Playback");
			play.setEnabled(state == RenderReplayPlugin.State.PLAYING
				|| (state == RenderReplayPlugin.State.IDLE && hasRecording));
			status.setText("<html>" + message + "</html>");
		});
	}

	/** Enables the transport buttons during playback and shows Pause/Play and the game tick being shown. */
	void updatePlayback(boolean playing, boolean paused, int tick)
	{
		SwingUtilities.invokeLater(() ->
		{
			pause.setEnabled(playing);
			pause.setText(paused ? "Play" : "Pause");
			stepBack.setEnabled(playing);
			stepForward.setEnabled(playing);
			tickNumber = playing ? tick : -1;
		});
	}

	/** Follows playback; leaves the slider alone while the user is dragging it. */
	void updateTimeline(long posMs, long spanMs, boolean enabled)
	{
		SwingUtilities.invokeLater(() ->
		{
			timeline.setEnabled(enabled && spanMs > 0);
			if (timeline.getValueIsAdjusting())
			{
				return;
			}
			programmatic = true;
			try
			{
				timeline.setMaximum((int) Math.max(1, spanMs));
				timeline.setValue((int) Math.min(posMs, spanMs));
				time.setText(timeText(posMs, spanMs, tickNumber));
			}
			finally
			{
				programmatic = false;
			}
		});
	}

	/** Nearest tick (600 ms) to a slider position; dragging seeks once per distinct value of this. */
	static int dragTick(long posMs)
	{
		return (int) Math.round(posMs / 600.0);
	}

	void setDragSeek(boolean on)
	{
		dragSeekEnabled = on;
	}

	static String timeText(long posMs, long spanMs, int tick)
	{
		String head = tick >= 0 ? "tick " + tick + " · " : "";
		return "<html>" + head + String.format(java.util.Locale.ROOT, "%.1f / %.1f ticks", posMs / 600.0, spanMs / 600.0)
			+ "<br>" + String.format(java.util.Locale.ROOT, "%.1f s / %.1f s", posMs / 1000.0, spanMs / 1000.0) + "</html>";
	}
}
