// SPDX-License-Identifier: Unlicense
package org.sibalsa.core;

public interface ProgressSink { void onStep(int completed, int total) throws Exception; }
