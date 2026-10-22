package com.survivalcinema.director.render;

import com.survivalcinema.director.camera.ShotType;
import com.survivalcinema.director.camera.Trail;
import com.survivalcinema.director.camera.TrailSample;
import com.survivalcinema.director.event.EventType;
import com.survivalcinema.director.story.EditDecisionList;

import java.util.ArrayList;
import java.util.List;

final class RenderTestData {
	private RenderTestData() {
	}

	static EditDecisionList.Item clip(String replayId, int session, int start, int end) {
		return clip(replayId, session, start, end, ShotType.ORBIT, -1, null);
	}

	static EditDecisionList.Item clip(String replayId, int session, int start, int end, ShotType shot, int slowFrom, String partner) {
		return new EditDecisionList.Item(EditDecisionList.Kind.CLIP, 0, 1, 1, null, null, replayId, session, start, end,
			shot, 6, 1.5, 120, 1, slowFrom, EventType.KILL, "test", 1, partner, start, false);
	}

	static EditDecisionList.Item card(EditDecisionList.Kind kind) {
		return EditDecisionList.Item.card(kind, 3, 1, 1, null, null);
	}

	static EditDecisionList edl(long createdAt, List<EditDecisionList.Item> items) {
		return new EditDecisionList(EditDecisionList.VERSION, "Мир", "Steve", 300, createdAt, items);
	}

	/** Walking east at 4 blocks per second, one sample every 2 ticks. */
	static Trail walkingEast(int fromTick, int toTick, double z) {
		List<TrailSample> samples = new ArrayList<>();
		for (int t = fromTick; t <= toTick; t += 2) {
			samples.add(new TrailSample(t, t * 0.2, 64, z, -90f, 0f, TrailSample.FLAG_ON_GROUND));
		}
		return new Trail(samples);
	}
}
