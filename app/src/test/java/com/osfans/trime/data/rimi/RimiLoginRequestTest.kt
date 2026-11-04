/*
 * SPDX-FileCopyrightText: 2026 Rimi community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.data.rimi

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe

/**
 * 「去登录」直达登录界面的请求必须是一次性的：
 * 消费一次就清掉，避免用户从登录页返回后又被自动弹一次登录框。
 */
class RimiLoginRequestTest :
    BehaviorSpec({
        Given("一次性的登录界面请求") {
            Then("未请求时不触发") {
                // 先清掉其它用例可能留下的状态
                RimiLoginRequest.consume() shouldBe false
                RimiLoginRequest.consume() shouldBe false
            }
            Then("请求一次只消费一次") {
                RimiLoginRequest.request()
                RimiLoginRequest.consume() shouldBe true
                RimiLoginRequest.consume() shouldBe false
            }
        }
    })
