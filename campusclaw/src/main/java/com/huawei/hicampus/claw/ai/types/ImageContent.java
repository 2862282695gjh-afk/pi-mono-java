/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.hicampus.claw.ai.types;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * An image content block carrying base64-encoded image data.
 *
 * @param data     base64-encoded image data
 * @param mimeType the MIME type of the image (e.g. "image/png")
 *
 * @version [br_eCampusCore 26.0.0, 2026/05/06]
 * @since [br_eCampusCore 26.0.0]
 */
public record ImageContent(@JsonProperty("data") String data, @JsonProperty("mimeType") String mimeType)
        implements ContentBlock {}
