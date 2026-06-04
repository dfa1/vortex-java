package io.github.dfa1.vortex.scan;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RowFilterTest {

	@Test
	void and_instanceMethod_combinesTwoFilters() {
		// Given
		RowFilter left = RowFilter.gte("price", 100);
		RowFilter right = RowFilter.lte("price", 500);

		// When
		RowFilter sut = left.and(right);

		// Then
		assertThat(sut).isInstanceOf(RowFilter.And.class);
		assertThat(((RowFilter.And) sut).filters()).isEqualTo(List.of(left, right));
	}

	@Test
	void and_instanceMethod_chainsMultiple() {
		// Given / When
		RowFilter sut = RowFilter.gte("price", 10)
				.and(RowFilter.lte("price", 500))
				.and(RowFilter.eq("active", true));

		// Then — nested And(And(gte, lte), eq)
		assertThat(sut).isInstanceOf(RowFilter.And.class);
		RowFilter.And outer = (RowFilter.And) sut;
		assertThat(outer.filters()).hasSize(2);
		assertThat(outer.filters().get(0)).isInstanceOf(RowFilter.And.class);
		assertThat(outer.filters().get(1)).isInstanceOf(RowFilter.Eq.class);
	}
}
