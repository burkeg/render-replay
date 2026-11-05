package com.renderreplay;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Lossless inter-frame compression of the per-frame call list written to *.draws.jsonl.
 * Consecutive frames repeat almost every call (about 98% measured), so a frame is written as a JSON array whose
 * elements are either a full call row or {"p":[start,len]}: "calls start..start+len-1 of the previous frame".
 * Decoding a frame needs only the previous decoded frame; every KEYFRAME_EVERY frames a frame is written in full so a
 * file can also be read from the middle.
 */
final class DrawDelta
{
	static final int KEYFRAME_EVERY = 120;

	private List<String> prev = new ArrayList<>();
	private int sinceKey;

	/** rows = each call already serialised as JSON; returns the JSON array text for the frame. */
	String encode(List<String> rows)
	{
		boolean key = sinceKey == 0 || prev.isEmpty();
		StringBuilder sb = new StringBuilder(key ? rows.size() * 64 : 256).append('[');
		if (key)
		{
			for (int i = 0; i < rows.size(); i++)
			{
				sb.append(i == 0 ? "" : ",").append(rows.get(i));
			}
		}
		else
		{
			Map<String, Integer> index = new HashMap<>();
			for (int i = prev.size() - 1; i >= 0; i--)
			{
				index.put(prev.get(i), i); // first occurrence wins
			}
			int runStart = -1, runLen = 0;
			boolean first = true;
			for (String r : rows)
			{
				if (runLen > 0 && runStart + runLen < prev.size() && prev.get(runStart + runLen).equals(r))
				{
					runLen++;
					continue;
				}
				if (runLen > 0)
				{
					sb.append(first ? "" : ",").append("{\"p\":[").append(runStart).append(',').append(runLen).append("]}");
					first = false;
					runLen = 0;
				}
				Integer at = index.get(r);
				if (at != null)
				{
					runStart = at;
					runLen = 1;
				}
				else
				{
					sb.append(first ? "" : ",").append(r);
					first = false;
				}
			}
			if (runLen > 0)
			{
				sb.append(first ? "" : ",").append("{\"p\":[").append(runStart).append(',').append(runLen).append("]}");
			}
		}
		prev = new ArrayList<>(rows);
		sinceKey = (sinceKey + 1) % KEYFRAME_EVERY;
		return sb.append(']').toString();
	}

	/**
	 * Inverse of encode for tests and tools: elems are the frame's array elements as JSON text ({"p":..} objects are
	 * recognised by their prefix); prevRows is the previous decoded frame.
	 */
	static List<String> decode(List<String> elems, List<String> prevRows)
	{
		List<String> out = new ArrayList<>();
		for (String e : elems)
		{
			if (e.startsWith("{\"p\":["))
			{
				String[] n = e.substring(6, e.indexOf(']')).split(",");
				int start = Integer.parseInt(n[0]), len = Integer.parseInt(n[1]);
				out.addAll(prevRows.subList(start, start + len));
			}
			else
			{
				out.add(e);
			}
		}
		return out;
	}
}
