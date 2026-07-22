package org.eardream.devvault.user.dto;

import org.springframework.core.io.Resource;
import org.springframework.http.MediaType;

public record ProfileImage(
        Resource resource,
        MediaType mediaType
) {}