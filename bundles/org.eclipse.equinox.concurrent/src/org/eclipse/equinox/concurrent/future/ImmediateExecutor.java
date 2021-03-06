/******************************************************************************
 * Copyright (c) 2010, 2013 EclipseSource and others. All rights reserved. This
 * program and the accompanying materials are made available under the terms of
 * the Eclipse Public License v1.0 which accompanies this distribution, and is
 * available at http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *   EclipseSource - initial API and implementation
 *   Gunnar Wagenknecht - added support for generics
 ******************************************************************************/
package org.eclipse.equinox.concurrent.future;

import org.eclipse.core.runtime.Assert;
import org.eclipse.core.runtime.IProgressMonitor;

/**
 * <p>
 * Executes {@link IProgressRunnable} instances immediately.
 * </p>
 * <p>
 * <b>NOTE</b>: {@link #execute(IProgressRunnable, IProgressMonitor)} should be
 * used with some degree of caution with this implementation, as unlike other
 * implementations the {@link IProgressRunnable#run(IProgressMonitor)} method
 * will be called by the thread that calls
 * {@link #execute(IProgressRunnable, IProgressMonitor)}, meaning that calling
 * #execute(IProgressRunnable, IProgressMonitor) may block the calling thread
 * indefinitely.
 * </p>
 * 
 * @see ThreadsExecutor
 * @since 1.1
 */
public class ImmediateExecutor extends AbstractExecutor implements IExecutor,
		IRunnableExecutor {

	@SuppressWarnings("rawtypes")
	protected AbstractFuture<?> createFuture(IProgressMonitor monitor) {
		return new SingleOperationListenableFuture(monitor);
	}

	@SuppressWarnings("unchecked")
	public <ResultType> IFuture<ResultType> execute(
			IProgressRunnable<? extends ResultType> runnable,
			IProgressMonitor monitor) {
		Assert.isNotNull(runnable);
		@SuppressWarnings("rawtypes")
		AbstractFuture sof = createFuture(monitor);
		// Actually run the runnable immediately. See NOTE above
		sof.runWithProgress(runnable);
		return sof;
	}

}
