package com.lezi.babylog.sync.backend

internal fun testPullPage(
    pageIndex: Int = 0,
    encoding: PullResponseEncoding = PullResponseEncoding.Identity,
    budget: PullPageBudget = FROZEN_PULL_PAGE_BUDGET,
) = PullPageRequest(pageIndex, encoding, budget)
