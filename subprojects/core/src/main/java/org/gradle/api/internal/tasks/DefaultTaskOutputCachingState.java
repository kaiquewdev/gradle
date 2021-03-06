/*
 * Copyright 2017 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.gradle.api.internal.tasks;

import org.gradle.api.internal.TaskOutputCachingState;

import static com.google.common.base.Preconditions.checkNotNull;
import static org.gradle.api.internal.tasks.TaskOutputCachingDisabledReasonCategory.BUILD_CACHE_DISABLED;

class DefaultTaskOutputCachingState implements TaskOutputCachingState {
    static final TaskOutputCachingState ENABLED = new DefaultTaskOutputCachingState(null);
    static final TaskOutputCachingState DISABLED = disabled(BUILD_CACHE_DISABLED.reason("Task output caching is disabled"));

    static TaskOutputCachingState disabled(TaskOutputCachingDisabledReason disabledReason) {
        checkNotNull(disabledReason, "disabledReason must be set if task output caching is disabled");
        return new DefaultTaskOutputCachingState(disabledReason);
    }

    private final TaskOutputCachingDisabledReason disabledReason;

    private DefaultTaskOutputCachingState(TaskOutputCachingDisabledReason disabledReason) {
        this.disabledReason = disabledReason;
    }

    @Override
    public boolean isEnabled() {
        return disabledReason == null;
    }

    @Override
    public String getDisabledReason() {
        return disabledReason == null ? null : disabledReason.getDescription();
    }

    @Override
    public TaskOutputCachingDisabledReason getReason() {
        return disabledReason;
    }

    @Override
    public String toString() {
        return "DefaultTaskOutputCachingState{"
            + "disabledReason='" + disabledReason + '\''
            + '}';
    }
}
