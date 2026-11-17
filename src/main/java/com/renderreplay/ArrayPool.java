package com.renderreplay;

import com.google.common.collect.MapMaker;
import java.util.Arrays;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Shares equal arrays between the model copies of one recording. An animated model gets a new copy for every pose,
 * and those copies differ only in their vertex positions: faces, colours and the rest are the same arrays again.
 * Arrays are held weakly, so they go away with the last frame that uses them. Shared arrays must not be written to.
 */
final class ArrayPool
{
	private final ConcurrentMap<Long, Object> arrays = new MapMaker().weakValues().makeMap();
	private final AtomicLong added = new AtomicLong();

	int[] share(int[] a)
	{
		if (a == null)
		{
			return null;
		}
		Long key = key(0, a.length, Arrays.hashCode(a));
		Object hit = arrays.get(key);
		if (hit instanceof int[] && Arrays.equals((int[]) hit, a))
		{
			return (int[]) hit;
		}
		return keep(key, a, 4L * a.length);
	}

	float[] share(float[] a)
	{
		if (a == null)
		{
			return null;
		}
		Long key = key(1, a.length, Arrays.hashCode(a));
		Object hit = arrays.get(key);
		if (hit instanceof float[] && Arrays.equals((float[]) hit, a))
		{
			return (float[]) hit;
		}
		return keep(key, a, 4L * a.length);
	}

	short[] share(short[] a)
	{
		if (a == null)
		{
			return null;
		}
		Long key = key(2, a.length, Arrays.hashCode(a));
		Object hit = arrays.get(key);
		if (hit instanceof short[] && Arrays.equals((short[]) hit, a))
		{
			return (short[]) hit;
		}
		return keep(key, a, 2L * a.length);
	}

	byte[] share(byte[] a)
	{
		if (a == null)
		{
			return null;
		}
		Long key = key(3, a.length, Arrays.hashCode(a));
		Object hit = arrays.get(key);
		if (hit instanceof byte[] && Arrays.equals((byte[]) hit, a))
		{
			return (byte[]) hit;
		}
		return keep(key, a, a.length);
	}

	/** Counts bytes kept for a recording outside the pool, so one total covers a frame's models. */
	void added(long bytes)
	{
		added.addAndGet(bytes);
	}

	/** Bytes added since the last call. */
	long takeAdded()
	{
		return added.getAndSet(0);
	}

	private static Long key(int type, int length, int hash)
	{
		return ((long) hash << 32) | ((long) length << 2 & 0xFFFFFFFCL) | type;
	}

	private <T> T keep(Long key, T a, long bytes)
	{
		arrays.put(key, a);
		added.addAndGet(16 + bytes);
		return a;
	}
}
