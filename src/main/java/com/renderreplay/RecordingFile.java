package com.renderreplay;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/** A parsed .rrec recording (zip): manifest, per-frame table, draw rows and model geometry. Pure data, no client access. */
final class RecordingFile
{
	static final int FRAMES_MAGIC = 0x52523202; // "RR" + format 2

	static final class Frame
	{
		long wallMs;
		int tick, scale;
		boolean keyframe;
		float playerX, playerZ;
		byte[] uiDelta;
	}

	JsonObject manifest;
	final List<Frame> frames = new ArrayList<>();
	/** All draw frames of the recording (rows fully expanded); the last frames.size() of them belong to the frame table. */
	final List<JsonArray> drawFrames = new ArrayList<>();
	final Map<Integer, JsonObject> models = new HashMap<>();
	int baseX = Integer.MIN_VALUE, baseY = Integer.MIN_VALUE;

	static RecordingFile parse(byte[] zip) throws IOException
	{
		Map<String, byte[]> files = new HashMap<>();
		try (ZipInputStream z = new ZipInputStream(new ByteArrayInputStream(zip)))
		{
			for (ZipEntry e; (e = z.getNextEntry()) != null; )
			{
				files.put(e.getName(), readAll(z));
			}
		}
		RecordingFile r = new RecordingFile();
		byte[] mf = files.get("manifest.json");
		if (mf == null)
		{
			throw new IOException("no manifest.json: not a Render Replay recording");
		}
		r.manifest = new JsonParser().parse(new String(mf, StandardCharsets.UTF_8)).getAsJsonObject();
		byte[] fb = files.get("frames.bin");
		if (fb == null)
		{
			throw new IOException("no frames.bin");
		}
		DataInputStream d = new DataInputStream(new ByteArrayInputStream(fb));
		if (d.readInt() != FRAMES_MAGIC)
		{
			throw new IOException("recording was made by an older Render Replay (no data to play it from a file)");
		}
		int n = d.readInt();
		for (int i = 0; i < n; i++)
		{
			Frame f = new Frame();
			f.wallMs = d.readLong();
			f.tick = d.readInt();
			f.scale = d.readInt();
			f.keyframe = d.readByte() != 0;
			d.readLong(); // unused
			d.readLong();
			f.playerX = d.readFloat();
			f.playerZ = d.readFloat();
			byte[] u = new byte[d.readInt()];
			d.readFully(u);
			f.uiDelta = u;
			r.frames.add(f);
		}
		byte[] models = files.get("recording.models.jsonl");
		if (models != null)
		{
			for (String line : new String(models, StandardCharsets.UTF_8).split("\n"))
			{
				if (line.isEmpty())
				{
					continue;
				}
				JsonObject o = new JsonParser().parse(line).getAsJsonObject();
				String kind = o.get("kind").getAsString();
				if (kind.equals("scene"))
				{
					if (r.baseX == Integer.MIN_VALUE)
					{
						r.baseX = o.get("baseX").getAsInt();
						r.baseY = o.get("baseY").getAsInt();
					}
				}
				else
				{
					r.models.put(o.get("id").getAsInt(), o);
				}
			}
		}
		byte[] draws = files.get("recording.draws.jsonl");
		if (draws == null)
		{
			throw new IOException("no draws file");
		}
		JsonArray prev = new JsonArray();
		for (String line : new String(draws, StandardCharsets.UTF_8).split("\n"))
		{
			if (line.isEmpty())
			{
				continue;
			}
			JsonObject o = new JsonParser().parse(line).getAsJsonObject();
			JsonArray calls = o.getAsJsonArray("calls");
			if (o.has("enc") && o.get("enc").getAsString().equals("d1"))
			{
				JsonArray full = new JsonArray();
				for (JsonElement e : calls)
				{
					if (e.isJsonObject() && e.getAsJsonObject().has("p"))
					{
						JsonArray p = e.getAsJsonObject().getAsJsonArray("p");
						int start = p.get(0).getAsInt(), len = p.get(1).getAsInt();
						for (int k = start; k < start + len; k++)
						{
							full.add(prev.get(k));
						}
					}
					else
					{
						full.add(e);
					}
				}
				calls = full;
			}
			r.drawFrames.add(calls);
			prev = calls;
		}
		if (r.drawFrames.size() < r.frames.size())
		{
			throw new IOException("draw data has fewer frames than the frame table");
		}
		return r;
	}

	private static byte[] readAll(InputStream in) throws IOException
	{
		ByteArrayOutputStream bos = new ByteArrayOutputStream();
		byte[] buf = new byte[1 << 16];
		for (int n; (n = in.read(buf)) > 0; )
		{
			bos.write(buf, 0, n);
		}
		return bos.toByteArray();
	}
}
