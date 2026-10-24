package iris.tools.airlift.monitor

import kotlin.test.*

class VerticalGridTest {
    @Test fun `columns are added only after filling all available rows`() {
        val single = WorkerGrid(220, 12, 12)
        assertEquals(1, single.columns)
        assertEquals(88, single.cellWidth)
        assertEquals(0 to 11, single.position(11))
        val two = WorkerGrid(220, 13, 12)
        assertEquals(2, two.columns)
        assertEquals(12, two.rows)
        assertEquals(0 to 11, two.position(11))
        assertEquals(two.cellWidth + WorkerGrid.GAP to 0, two.position(12))
        assertEquals(3, WorkerGrid(220, 25, 12).columns)
    }

    @Test fun `height resize collapses columns and narrow screens retain paging`() {
        assertEquals(2, WorkerGrid(220, 16, 12).columns)
        assertEquals(1, WorkerGrid(220, 16, 20).columns)
        val narrow = WorkerGrid(80, 16, 12)
        assertEquals(1, narrow.columns)
        assertEquals(12, narrow.capacity)
        assertEquals(0 to 0, narrow.position(12))
    }
}
