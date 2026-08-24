/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.campusclaw.tui.component;

/**
 * Border character sets for {@link Box}.
 *
 * @version [br_eCampusCore 26.0.0, 2026/05/06]
 * @since [br_eCampusCore 26.0.0]
 */
public enum BorderStyle {
    SINGLE('┌', '┐', '└', '┘', '─', '│'),
    DOUBLE('╔', '╗', '╚', '╝', '═', '║'),
    ROUNDED('╭', '╮', '╰', '╯', '─', '│');

    final char topLeft;
    final char topRight;
    final char bottomLeft;
    final char bottomRight;
    final char horizontal;
    final char vertical;

    BorderStyle(char topLeft, char topRight, char bottomLeft, char bottomRight, char horizontal, char vertical) {
        this.topLeft = topLeft;
        this.topRight = topRight;
        this.bottomLeft = bottomLeft;
        this.bottomRight = bottomRight;
        this.horizontal = horizontal;
        this.vertical = vertical;
    }
}
