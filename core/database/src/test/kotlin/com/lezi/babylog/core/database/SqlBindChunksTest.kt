package com.lezi.babylog.core.database

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.Test

class SqlBindChunksTest {
    @Test
    fun chunksStayUnderTheSqliteBindLimitAndKeepInputOrder() {
        runBlocking {
        val ids = (1L..600L).toList()
        val sizes = mutableListOf<Int>()
        val rows = queryInChunks(ids) { chunk ->
            check(chunk.size <= SQL_BIND_CHUNK)
            sizes += chunk.size
            chunk
        }
        assertThat(sizes).containsExactly(500, 100).inOrder()
        assertThat(rows).containsExactlyElementsIn(ids).inOrder()
        }
    }
}
