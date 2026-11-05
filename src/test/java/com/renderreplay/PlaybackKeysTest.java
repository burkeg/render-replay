package com.renderreplay;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.awt.Canvas;
import java.awt.event.KeyEvent;
import org.junit.Test;

public class PlaybackKeysTest
{
	private static final Canvas SRC = new Canvas();

	private static KeyEvent key(int id)
	{
		// KEY_TYPED events must carry VK_UNDEFINED; pressed/released carry the real key code
		return new KeyEvent(SRC, id, 0L, 0, id == KeyEvent.KEY_TYPED ? KeyEvent.VK_UNDEFINED : KeyEvent.VK_A, 'a');
	}

	@Test
	public void nothingIsConsumedWhenPlaybackIsNotActive()
	{
		PlaybackKeys b = new PlaybackKeys(() -> { }, (d, r) -> { });
		KeyEvent k = press(KeyEvent.VK_LEFT);
		b.keyPressed(k);
		assertFalse(k.isConsumed());
	}

	@Test
	public void onlyTheThreeTransportKeysAreConsumedWhilePlaying()
	{
		PlaybackKeys b = new PlaybackKeys(() -> { }, (d, r) -> { });
		b.setActive(true);
		KeyEvent a = key(KeyEvent.KEY_PRESSED), ar = key(KeyEvent.KEY_RELEASED), at = key(KeyEvent.KEY_TYPED);
		b.keyPressed(a);
		b.keyReleased(ar);
		b.keyTyped(at);
		assertFalse("other keys always reach the game", a.isConsumed() || ar.isConsumed() || at.isConsumed());
		KeyEvent l = press(KeyEvent.VK_LEFT), r = press(KeyEvent.VK_RIGHT), s = press(KeyEvent.VK_SPACE);
		b.keyPressed(l);
		b.keyPressed(r);
		b.keyPressed(s);
		assertTrue(l.isConsumed() && r.isConsumed() && s.isConsumed());
		b.setActive(false);
		KeyEvent l2 = press(KeyEvent.VK_LEFT);
		b.keyPressed(l2);
		assertFalse("playback over: keys flow to the game again", l2.isConsumed());
	}

	private static KeyEvent press(int code)
	{
		return new KeyEvent(SRC, KeyEvent.KEY_PRESSED, 0L, 0, code, code == KeyEvent.VK_SPACE ? ' ' : KeyEvent.CHAR_UNDEFINED);
	}

	private static KeyEvent release(int code)
	{
		return new KeyEvent(SRC, KeyEvent.KEY_RELEASED, 0L, 0, code, code == KeyEvent.VK_SPACE ? ' ' : KeyEvent.CHAR_UNDEFINED);
	}

	@Test
	public void arrowKeysStepAndSpaceTogglesOnlyWhilePlaying()
	{
		java.util.List<String> log = new java.util.ArrayList<>();
		PlaybackKeys b = new PlaybackKeys(() -> log.add("toggle"), (d, r) -> log.add("step" + d + (r ? "r" : "")));

		KeyEvent left = press(KeyEvent.VK_LEFT);
		b.keyPressed(left);
		assertFalse("not playing: key passes through", left.isConsumed());
		assertTrue(log.isEmpty());

		b.setActive(true);
		KeyEvent l2 = press(KeyEvent.VK_LEFT), r2 = press(KeyEvent.VK_RIGHT), r3 = press(KeyEvent.VK_RIGHT);
		b.keyPressed(l2);
		b.keyPressed(r2);
		b.keyPressed(r3);            // Right still down: this one is an auto-repeat
		assertTrue(l2.isConsumed() && r2.isConsumed());
		assertEquals("a fresh press, then an auto-repeat flagged as such", java.util.Arrays.asList("step-1", "step1", "step1r"), log);

		log.clear();
		b.keyPressed(press(KeyEvent.VK_SPACE));
		b.keyPressed(press(KeyEvent.VK_SPACE));   // auto-repeat
		assertEquals("space auto-repeat must not toggle repeatedly", java.util.Arrays.asList("toggle"), log);
		b.keyReleased(release(KeyEvent.VK_SPACE));
		b.keyPressed(press(KeyEvent.VK_SPACE));
		assertEquals("a fresh press after release toggles again", java.util.Arrays.asList("toggle", "toggle"), log);

		log.clear();
		KeyEvent a = new KeyEvent(SRC, KeyEvent.KEY_PRESSED, 0L, 0, KeyEvent.VK_A, 'a');
		b.keyPressed(a);
		assertFalse("any other key is left alone", a.isConsumed());
		assertTrue("and does nothing", log.isEmpty());
	}

	@Test
	public void holdingLeftRewindsSteadilyNotThirtyTicksASecond() throws Exception
	{
		java.util.List<String> log = new java.util.ArrayList<>();
		PlaybackKeys b = new PlaybackKeys(() -> { }, (d, r) -> log.add("step" + d + (r ? "r" : "")));
		b.setActive(true);
		b.keyPressed(press(KeyEvent.VK_LEFT));                    // fresh press: immediate
		for (int i = 0; i < 12; i++)
		{
			b.keyPressed(press(KeyEvent.VK_LEFT));                // a burst of auto-repeats within the throttle window
		}
		assertEquals("only the first press acts inside the window", java.util.Arrays.asList("step-1"), log);
		b.lastLeftMs -= PlaybackKeys.LEFT_REPEAT_MS + 40;         // as if the throttle window had passed
		b.keyPressed(press(KeyEvent.VK_LEFT));                    // still held: one more rewind step
		assertEquals(java.util.Arrays.asList("step-1", "step-1r"), log);
		b.keyReleased(release(KeyEvent.VK_LEFT));
		b.keyPressed(press(KeyEvent.VK_LEFT));                    // a new tap acts immediately again
		assertEquals("step-1", log.get(log.size() - 1));
		assertEquals(3, log.size());
	}
}
