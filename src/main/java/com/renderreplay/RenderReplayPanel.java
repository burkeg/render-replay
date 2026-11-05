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
	private final JLabel hint = new JLabel("<html>Playing: Left/Right = 1 tick, Space = pause</html>");
	private int lastDragTick = -1;
	private boolean dragging;
	private volatile java.util.function.Consumer<Boolean> onDragState = b -> { };

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
		importBtn.setToolTipText("Read a recording from the clipboard");
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
		for (Object[] r : new Object[][]{{slots, 28}, {files, 28}, {record, 32}, {play, 32}, {transport, 32}, {timeline, 26}, {time, 34}, {status, 78}, {hint, 30}})
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

	void update(RenderReplayPlugin.State state, boolean hasRecording, String message)
	{
		SwingUtilities.invokeLater(() ->
		{
			record.setText(state == RenderReplayPlugin.State.RECORDING ? "Stop recording" : "Record");
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
