package com.propertyapp.floorplan;

import static org.assertj.core.api.Assertions.assertThat;

import com.propertyapp.floorplan.FloorPlanReader.Check;
import com.propertyapp.floorplan.FloorPlanReader.Declared;
import com.propertyapp.floorplan.FloorPlanReader.Reading;
import com.propertyapp.floorplan.FloorPlanReader.Room;
import java.util.List;
import org.junit.jupiter.api.Test;

class FloorPlanReaderTest {

	static Room room(String type, Double area) {
		return new Room(type, type, area, area == null ? "none" : "printed", null, null, "", 0.9, List.of());
	}

	static Reading reading(List<Room> rooms, Double total) {
		return new Reading("p1", "m", "m", true, "clear", "metres", "1:50", rooms, total, "", List.of(), "AI transcription");
	}

	static final List<Room> FLAT = List.of(room("living-room", 24.0), room("kitchen", 9.0), room("bedroom", 12.4),
			room("bedroom", 11.0), room("bathroom", 5.0), room("hall", 4.6), room("balcony", 9.0));

	@Test
	void consistentPlan() {
		var r = FloorPlanReader.check(reading(FLAT, 78.0), new Declared(78.0, 2, 1));
		assertThat(r.roomsAreaSqm()).isEqualTo(66.0);
		assertThat(r.outdoorAreaSqm()).isEqualTo(9.0);
		assertThat(r.checks()).extracting(Check::check, Check::status).containsExactly(
				org.assertj.core.groups.Tuple.tuple("Total area", "ok"),
				org.assertj.core.groups.Tuple.tuple("Bedrooms", "ok"),
				org.assertj.core.groups.Tuple.tuple("Bathrooms and WCs", "ok"));
		assertThat(r.checks().getFirst().detail()).contains("85% of the declared 78 m²");
	}

	@Test
	void mismatchesAndUnknownAreas() {
		var rooms = new java.util.ArrayList<>(FLAT);
		rooms.add(room("bedroom", null));
		var r = FloorPlanReader.check(reading(rooms, 95.0), new Declared(60.0, 2, 2));
		assertThat(r.roomsWithoutArea()).isEqualTo(1);
		assertThat(r.checks()).extracting(Check::check, Check::status).contains(
				org.assertj.core.groups.Tuple.tuple("Room areas", "check"),
				org.assertj.core.groups.Tuple.tuple("Total area", "mismatch"),
				org.assertj.core.groups.Tuple.tuple("Printed total", "mismatch"),
				org.assertj.core.groups.Tuple.tuple("Bedrooms", "mismatch"),
				org.assertj.core.groups.Tuple.tuple("Bathrooms and WCs", "mismatch"));
	}

	@Test
	void notAFloorPlan() {
		var r = FloorPlanReader.check(new Reading("p", "m", "m", false, "clear", "not-shown", null, List.of(), null, "", List.of(), ""), null);
		assertThat(r.checks()).singleElement().extracting(Check::status).isEqualTo("mismatch");
	}
}
