package com.renderreplay;

import java.awt.event.KeyEvent;
import net.runelite.client.input.KeyListener;

/**
 * Playback transport keys: Left / Right = one tick back / forward, Space = pause / resume, only while a playback is running.
 * Only those three keys are consumed. The mouse, the mouse wheel and every other key always reach the game. This never
 * creates or injects any event.
 */
final class PlaybackKeys implements KeyListener
{
	private volatile boolean active;
	/** Holding Left rewinds at most one tick per this many ms (auto-repeat fires ~30 times a second). */
	static final long LEFT_REPEAT_MS = 200;

	/** dir: -1 back, +1 forward. repeat: this is a key auto-repeat, not a fresh press. */
	interface StepAction
	{
		void step(int dir, boolean repeat);
	}

	private final Runnable onToggle;
	private final StepAction onStep;
	private boolean spaceDown;  // ignore key auto-repeat for Space
	private boolean leftDown;
	private boolean rightDown;
	private long lastLeftMs;

	PlaybackKeys(Runnable onToggle, StepAction onStep)
	{
		this.onToggle = onToggle;
		this.onStep = onStep;
	}

	void setActive(boolean active)
	{
		this.active = active;
	}

	private static boolean transportKey(int code)
	{
		return code == KeyEvent.VK_LEFT || code == KeyEvent.VK_RIGHT || code == KeyEvent.VK_SPACE;
	}

	@Override
	public void keyTyped(KeyEvent e)
	{
		if (active && e.getKeyChar() == ' ')
		{
			e.consume(); // the typed character of Space
		}
	}

	@Override
	public void keyPressed(KeyEvent e)
	{
		if (!active || !transportKey(e.getKeyCode()))
		{
			return;
		}
		e.consume();
		switch (e.getKeyCode())
		{
			case KeyEvent.VK_LEFT:
			{
				boolean repeat = leftDown;
				leftDown = true;
				long now = System.currentTimeMillis();
				if (!repeat || now - lastLeftMs >= LEFT_REPEAT_MS)
				{
					lastLeftMs = now;
					onStep.step(-1, repeat);
				}
				break;
			}
			case KeyEvent.VK_RIGHT:
			{
				boolean repeat = rightDown;
				rightDown = true;
				onStep.step(1, repeat); // holding Right keeps the animated step going (the recorder rate-limits repeats)
				break;
			}
			default: // VK_SPACE
				if (!spaceDown)
				{
					spaceDown = true;
					onToggle.run();
				}
				break;
		}
	}

	@Override
	public void keyReleased(KeyEvent e)
	{
		switch (e.getKeyCode())
		{
			case KeyEvent.VK_SPACE:
				spaceDown = false;
				break;
			case KeyEvent.VK_LEFT:
				leftDown = false;
				break;
			case KeyEvent.VK_RIGHT:
				rightDown = false;
				break;
			default:
				break;
		}
		if (active && transportKey(e.getKeyCode()))
		{
			e.consume();
		}
	}
}
