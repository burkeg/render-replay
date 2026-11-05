package com.renderreplay;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.awt.Component;
import java.awt.Container;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import javax.imageio.ImageIO;
import javax.swing.JButton;
import javax.swing.SwingUtilities;
import org.junit.BeforeClass;
import org.junit.Test;

/** The panel layout must be static: nothing may move or resize when the status text or playback state changes. */
public class RenderReplayPanelTest
{
	@BeforeClass
	public static void headless()
	{
		System.setProperty("java.awt.headless", "true");
	}

	private static void layoutAll(Container c)
	{
		c.doLayout();
		for (Component k : c.getComponents())
		{
			if (k instanceof Container)
			{
				layoutAll((Container) k);
			}
		}
	}

	private static void collect(Component c, Component root, List<Component> out)
	{
		out.add(c);
		if (c instanceof Container)
		{
			for (Component k : ((Container) c).getComponents())
			{
				collect(k, root, out);
			}
		}
	}

	private static Rectangle abs(Component c, Component root)
	{
		Rectangle r = SwingUtilities.convertRectangle(c.getParent() == null ? c : c.getParent(), c.getBounds(), root);
		return r;
	}

	private static List<Rectangle> snapshot(RenderReplayPanel p) throws Exception
	{
		SwingUtilities.invokeAndWait(() -> { });   // flush queued panel updates
		List<Rectangle> out = new ArrayList<>();
		SwingUtilities.invokeAndWait(() ->
		{
			layoutAll(p);
			List<Component> all = new ArrayList<>();
			collect(p, p, all);
			for (Component c : all)
			{
				out.add(abs(c, p));
			}
		});
		return out;
	}

	private static RenderReplayPanel panel()
	{
		RenderReplayPanel p = new RenderReplayPanel(() -> { }, () -> { }, ms -> { }, () -> { }, d -> { });
		p.setSize(225, 640);
		return p;
	}

	@Test
	public void layoutDoesNotMoveWhenStatusTextOrPlaybackStateChanges() throws Exception
	{
		RenderReplayPanel p = panel();
		p.update(RenderReplayPlugin.State.IDLE, false, "Idle");
		List<Rectangle> idle = snapshot(p);

		String longText = "Playing. Zoom differs: recorded 2218, yours 5254. Match your zoom for an exact picture. "
			+ "You are in a different area than the recording: terrain shown is your CURRENT area (recorded objects only).";
		p.update(RenderReplayPlugin.State.PLAYING, true, longText);
		p.updatePlayback(true, true, 852);
		p.updateTimeline(2300, 4800, true);
		List<Rectangle> busy = snapshot(p);

		p.updatePlayback(true, false, 853);   // Pause <-> Play label flip
		List<Rectangle> flipped = snapshot(p);

		assertEquals(idle.size(), busy.size());
		for (int i = 0; i < idle.size(); i++)
		{
			assertEquals("component " + i + " moved/resized between idle and playing with long status text", idle.get(i), busy.get(i));
			assertEquals("component " + i + " moved/resized when Pause flipped to Play", busy.get(i), flipped.get(i));
		}
	}

	@Test
	public void transportButtonsAreWideEnoughForTheirLabelsAndRendersToAnImage() throws Exception
	{
		RenderReplayPanel p = panel();
		p.update(RenderReplayPlugin.State.PLAYING, true, "Playing. Zoom differs: recorded 2218, yours 5254. Match your zoom for an exact picture.");
		p.updatePlayback(true, true, 852);
		p.updateTimeline(2300, 4800, true);
		snapshot(p);
		List<Component> all = new ArrayList<>();
		collect(p, p, all);
		for (Component c : all)
		{
			if (c instanceof JButton)
			{
				JButton b = (JButton) c;
				int need = b.getFontMetrics(b.getFont()).stringWidth(b.getText()) + b.getMargin().left + b.getMargin().right;
				assertTrue("button '" + b.getText() + "' is " + b.getWidth() + "px wide but needs " + need + " (would be truncated)", b.getWidth() >= need);
			}
		}
		BufferedImage img = new BufferedImage(225, 300, BufferedImage.TYPE_INT_RGB);
		Graphics2D g = img.createGraphics();
		g.setColor(new java.awt.Color(0x282828));
		g.fillRect(0, 0, 225, 300);
		SwingUtilities.invokeAndWait(() -> p.paint(g));
		g.dispose();
		new File("build").mkdirs();
		ImageIO.write(img, "png", new File("build/panel_preview.png"));
	}

	@Test
	public void timeLabelLeadsWithTicksAndAlwaysShowsSecondsWithOneDecimal()
	{
		assertEquals("<html>tick 852 · 4.0 / 8.0 ticks<br>2.4 s / 4.8 s</html>", RenderReplayPanel.timeText(2400, 4800, 852));
		assertEquals("whole seconds still get a decimal", "<html>0.0 / 10.0 ticks<br>0.0 s / 6.0 s</html>", RenderReplayPanel.timeText(0, 6000, -1));
		assertEquals("<html>1.0 / 1.0 ticks<br>0.6 s / 0.6 s</html>", RenderReplayPanel.timeText(600, 600, -1));
	}

	@org.junit.Test
	public void dragSeeksOncePerTick()
	{
		org.junit.Assert.assertEquals(0, RenderReplayPanel.dragTick(299));
		org.junit.Assert.assertEquals(1, RenderReplayPanel.dragTick(300));
		org.junit.Assert.assertEquals(1, RenderReplayPanel.dragTick(899));
		org.junit.Assert.assertEquals(2, RenderReplayPanel.dragTick(900));
	}

	@org.junit.Test
	public void slotChooserListsSlotsAndReportsSelection() throws Exception
	{
		RenderReplayPanel p = new RenderReplayPanel(() -> { }, () -> { }, ms -> { }, () -> { }, d -> { });
		java.util.List<Integer> picked = new java.util.ArrayList<>();
		p.setSlotHandlers(picked::add, () -> { }, () -> { });
		java.util.List<String> labels = java.util.Arrays.asList("Slot 1 · 20.0 ticks · 10:01", "Slot 2 · empty", "Slot 3 · empty", "Slot 4 · empty");
		p.updateSlots(labels, 0, true, true);
		javax.swing.SwingUtilities.invokeAndWait(() -> { });
		javax.swing.JComboBox<?> box = find(p, javax.swing.JComboBox.class);
		org.junit.Assert.assertEquals(4, box.getItemCount());
		org.junit.Assert.assertTrue(picked.isEmpty());   // programmatic selection must not fire the handler
		javax.swing.SwingUtilities.invokeAndWait(() -> box.setSelectedIndex(2));
		org.junit.Assert.assertEquals(java.util.Arrays.asList(2), picked);
	}

	private static <T> T find(java.awt.Container c, Class<T> type)
	{
		for (java.awt.Component k : c.getComponents())
		{
			if (type.isInstance(k))
			{
				return type.cast(k);
			}
			if (k instanceof java.awt.Container)
			{
				T r = find((java.awt.Container) k, type);
				if (r != null)
				{
					return r;
				}
			}
		}
		return null;
	}
}
