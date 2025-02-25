/*******************************************************************************
 * Copyright (c) 2000, 2021 IBM Corporation and others.
 *
 * This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License 2.0
 * which accompanies this distribution, and is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Contributors:
 *     IBM Corporation - initial API and implementation
 *     Terry Parker <tparker@google.com> (Google Inc.) - Bug 441016 - Speed up text search by parallelizing it using JobGroups
 *     Sergey Prigogin (Google) - Bug 489551 - File Search silently drops results on StackOverflowError
 *******************************************************************************/
package org.eclipse.search.internal.core.text;

import java.io.CharConversionException;
import java.io.IOException;
import java.nio.charset.IllegalCharsetNameException;
import java.nio.charset.UnsupportedCharsetException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.MultiStatus;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.core.runtime.OperationCanceledException;
import org.eclipse.core.runtime.Platform;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.SubMonitor;
import org.eclipse.core.runtime.content.IContentDescription;
import org.eclipse.core.runtime.content.IContentType;
import org.eclipse.core.runtime.content.IContentTypeManager;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.core.runtime.jobs.JobGroup;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IResourceStatus;
import org.eclipse.core.resources.ResourcesPlugin;

import org.eclipse.core.filebuffers.FileBuffers;
import org.eclipse.core.filebuffers.ITextFileBuffer;
import org.eclipse.core.filebuffers.ITextFileBufferManager;
import org.eclipse.core.filebuffers.LocationKind;

import org.eclipse.jface.text.IDocument;

import org.eclipse.ui.IEditorInput;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.IEditorReference;
import org.eclipse.ui.IFileEditorInput;
import org.eclipse.ui.IWorkbench;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PlatformUI;

import org.eclipse.ui.texteditor.ITextEditor;

import org.eclipse.search.core.text.TextSearchMatchAccess;
import org.eclipse.search.core.text.TextSearchRequestor;
import org.eclipse.search.core.text.TextSearchScope;
import org.eclipse.search.internal.core.text.FileCharSequenceProvider.FileCharSequenceException;
import org.eclipse.search.internal.ui.Messages;
import org.eclipse.search.internal.ui.SearchMessages;
import org.eclipse.search.internal.ui.SearchPlugin;
import org.eclipse.search.ui.NewSearchUI;

/**
 * The visitor that does the actual work.
 */
public class TextSearchVisitor {

	public static final boolean TRACING= "true".equalsIgnoreCase(Platform.getDebugOption("org.eclipse.search/perf")); //$NON-NLS-1$ //$NON-NLS-2$
	private static final int NUMBER_OF_LOGICAL_THREADS= Runtime.getRuntime().availableProcessors();
	private static final int FILES_PER_JOB= 50;
	private static final int MAX_JOBS_COUNT= 100;
	/**
	 * Just any number such that the most source files will fit in. And not too
	 * big to avoid out of memory.
	 **/
	private static final int MAX_BUFFER_LENGTH = 999_999; // max 2MB.

	public static class ReusableMatchAccess extends TextSearchMatchAccess {

		private int fOffset;
		private int fLength;
		private IFile fFile;
		private CharSequence fContent;

		public void initialize(IFile file, int offset, int length, CharSequence content) {
			fFile= file;
			fOffset= offset;
			fLength= length;
			fContent= content;
		}

		@Override
		public IFile getFile() {
			return fFile;
		}

		@Override
		public int getMatchOffset() {
			return fOffset;
		}

		@Override
		public int getMatchLength() {
			return fLength;
		}

		@Override
		public int getFileContentLength() {
			return fContent.length();
		}

		@Override
		public char getFileContentChar(int offset) {
			return fContent.charAt(offset);
		}

		@Override
		public String getFileContent(int offset, int length) {
			return fContent.subSequence(offset, offset + length).toString(); // must pass a copy!
		}
	}

	/**
	 * A JobGroup for text searches across multiple files.
	 */
	private static class TextSearchJobGroup extends JobGroup {
		public TextSearchJobGroup(String name, int maxThreads, int initialJobCount) {
			super(name, maxThreads, initialJobCount);
		}

		// Always continue processing all other files, even if errors are encountered in individual files.
		@Override
		protected boolean shouldCancel(IStatus lastCompletedJobResult, int numberOfFailedJobs, int numberOfCancelledJobs) {
			return false;
		}
	}

	/**
	 * A job to find matches in a set of files.
	 */
	private class TextSearchJob extends Job {
		private final IFile[] fFiles;
		private final int fBegin;
		private final int fEnd;
		private final Map<IFile, IDocument> fDocumentsInEditors;
		private FileCharSequenceProvider fileCharSequenceProvider;

		private IPath previousLocationFromFile;
		// occurences need to be passed to FileSearchResultCollector with growing offset
		private List<TextSearchMatchAccess> occurencesForPreviousLocation;
		private CharSequence charsequenceForPreviousLocation;


		/**
		 * Searches for matches in a set of files.
		 *
		 * @param files an array of IFiles, a portion of which is to be processed
		 * @param begin the first element in the file array to process
		 * @param end one past the last element in the array to process
		 * @param documentsInEditors a map from IFile to IDocument for all open, dirty editors
		 */
		public TextSearchJob(IFile[] files, int begin, int end, Map<IFile, IDocument> documentsInEditors) {
			super(files[begin].getName());
			setSystem(true);
			fFiles= files;
			fBegin= begin;
			fEnd= end;
			fDocumentsInEditors= documentsInEditors;
		}

		@Override
		protected IStatus run(IProgressMonitor inner) {
			MultiStatus multiStatus=
					new MultiStatus(NewSearchUI.PLUGIN_ID, IStatus.OK, SearchMessages.TextSearchEngine_statusMessage, null);
			SubMonitor subMonitor= SubMonitor.convert(inner, fEnd - fBegin);
			this.fileCharSequenceProvider= new FileCharSequenceProvider();
			for (int i= fBegin; i < fEnd && !fFatalError; i++) {
				IStatus status= processFile(fFiles[i], subMonitor.split(1));
				// Only accumulate interesting status
				if (!status.isOK())
					multiStatus.add(status);
				// Group cancellation is propagated to this job's monitor.
				// Stop processing and return the status for the completed jobs.
			}
			if (charsequenceForPreviousLocation != null) {
				try {
					fileCharSequenceProvider.releaseCharSequence(charsequenceForPreviousLocation);
				} catch (IOException e) {
					SearchPlugin.log(e);
				} finally {
					charsequenceForPreviousLocation= null;
				}
			}
			fileCharSequenceProvider= null;
			previousLocationFromFile= null;
			occurencesForPreviousLocation= null;
			return multiStatus;
		}

		public IStatus processFile(IFile file, IProgressMonitor monitor) {
			// A natural cleanup after the change to use JobGroups is accepted would be to move these
			// methods to the TextSearchJob class.
			Matcher matcher= fSearchPattern.pattern().isEmpty() ? null : fSearchPattern.matcher(""); //$NON-NLS-1$

			try {
				if (!fCollector.acceptFile(file) || matcher == null) {
					return Status.OK_STATUS;
				}

				IDocument document= getOpenDocument(file, getDocumentsInEditors());
				if (document != null) {
					DocumentCharSequence documentCharSequence= new DocumentCharSequence(document);
					// assume all documents are non-binary
					locateMatches(file, documentCharSequence, matcher, monitor);
				} else if (previousLocationFromFile != null && previousLocationFromFile.equals(file.getLocation()) && !occurencesForPreviousLocation.isEmpty()) {
					// reuse previous result
					ReusableMatchAccess matchAccess= new ReusableMatchAccess();
					for (TextSearchMatchAccess occurence : occurencesForPreviousLocation) {
						matchAccess.initialize(file, occurence.getMatchOffset(), occurence.getMatchLength(), charsequenceForPreviousLocation);
						boolean goOn= fCollector.acceptPatternMatch(matchAccess);
						if (!goOn) {
							break;
						}
					}
					fCollector.flushMatches(file);
				} else {
					if (charsequenceForPreviousLocation != null) {
						try {
							fileCharSequenceProvider.releaseCharSequence(charsequenceForPreviousLocation);
							charsequenceForPreviousLocation= null;
						} catch (IOException e) {
							SearchPlugin.log(e);
						}
					}
					try {
						charsequenceForPreviousLocation= fileCharSequenceProvider.newCharSequence(file);
						if (hasBinaryContent(charsequenceForPreviousLocation, file) && !fCollector.reportBinaryFile(file)) {
							occurencesForPreviousLocation= Collections.emptyList();
							return Status.OK_STATUS;
						}
						occurencesForPreviousLocation= locateMatches(file, charsequenceForPreviousLocation, matcher, monitor);
						previousLocationFromFile= file.getLocation();
					} catch (FileCharSequenceProvider.FileCharSequenceException e) {
						e.throwWrappedException();
					}
				}
			} catch (UnsupportedCharsetException e) {
				String[] args= { getCharSetName(file), file.getFullPath().makeRelative().toString()};
				String message= Messages.format(SearchMessages.TextSearchVisitor_unsupportedcharset, args);
				return new Status(IStatus.ERROR, NewSearchUI.PLUGIN_ID, IStatus.ERROR, message, e);
			} catch (IllegalCharsetNameException e) {
				String[] args= { getCharSetName(file), file.getFullPath().makeRelative().toString()};
				String message= Messages.format(SearchMessages.TextSearchVisitor_illegalcharset, args);
				return new Status(IStatus.ERROR, NewSearchUI.PLUGIN_ID, IStatus.ERROR, message, e);
			} catch (IOException e) {
				String[] args= { getExceptionMessage(e), file.getFullPath().makeRelative().toString()};
				String message= Messages.format(SearchMessages.TextSearchVisitor_error, args);
				return new Status(IStatus.ERROR, NewSearchUI.PLUGIN_ID, IStatus.ERROR, message, e);
			} catch (CoreException e) {
				if (fIsLightweightAutoRefresh && IResourceStatus.RESOURCE_NOT_FOUND == e.getStatus().getCode()) {
					return monitor.isCanceled() ? Status.CANCEL_STATUS : Status.OK_STATUS;
				}
				String[] args= { getExceptionMessage(e), file.getFullPath().makeRelative().toString() };
				String message= Messages.format(SearchMessages.TextSearchVisitor_error, args);
				return new Status(IStatus.ERROR, NewSearchUI.PLUGIN_ID, IStatus.ERROR, message, e);
			} catch (StackOverflowError e) {
				fFatalError= true;
				String message= SearchMessages.TextSearchVisitor_patterntoocomplex0;
				return new Status(IStatus.ERROR, NewSearchUI.PLUGIN_ID, IStatus.ERROR, message, e);
			} finally {
				synchronized (fLock) {
					fCurrentFile= file;
					fNumberOfScannedFiles++;
				}
			}
			return Status.OK_STATUS;
		}

		public Map<IFile, IDocument> getDocumentsInEditors() {
			return fDocumentsInEditors;
		}

	}


	private final TextSearchRequestor fCollector;
	private final Pattern fSearchPattern;

	private IProgressMonitor fProgressMonitor;

	private int fNumberOfFilesToScan;
	private int fNumberOfScannedFiles;  // Protected by fLock
	private IFile fCurrentFile;  // Protected by fLock
	private Object fLock= new Object();

	private final MultiStatus fStatus;
	private volatile boolean fFatalError; // If true, terminates the search.

	private boolean fIsLightweightAutoRefresh;

	public TextSearchVisitor(TextSearchRequestor collector, Pattern searchPattern) {
		fCollector= collector;
		fStatus= new MultiStatus(NewSearchUI.PLUGIN_ID, IStatus.OK, SearchMessages.TextSearchEngine_statusMessage, null);

		fSearchPattern= searchPattern;

		fIsLightweightAutoRefresh= Platform.getPreferencesService().getBoolean(ResourcesPlugin.PI_RESOURCES, ResourcesPlugin.PREF_LIGHTWEIGHT_AUTO_REFRESH, false, null);
	}

	/**
	 * Just a record pair to avoid multiple file.getLocation() calls during
	 * sort.
	 **/
	private static final class FileWithCachedLocation {
		private final IFile file;
		private final String location; // cached

		private static Comparator<String> NULLS_FIRST = Comparator.nullsFirst(Comparator.naturalOrder());
		private static Comparator<FileWithCachedLocation> BY_LOCATION = Comparator
				.comparing(FileWithCachedLocation::getLocation, NULLS_FIRST);

		FileWithCachedLocation(IFile file) {
			this.file = file;
			IPath path = file.getLocation(); // invokes slow OS operation
			this.location = path == null ? null : path.toString();
		}

		String getLocation() {
			return location;
		}

		IFile getFile() {
			return file;
		}
	}

	public IStatus search(IFile[] files, IProgressMonitor monitor) {
		if (files.length == 0) {
			return fStatus;
		}
		fProgressMonitor= monitor == null ? new NullProgressMonitor() : monitor;
		fNumberOfScannedFiles= 0;
		fNumberOfFilesToScan= files.length;
		fCurrentFile= null;
		int maxThreads= fCollector.canRunInParallel() ? NUMBER_OF_LOGICAL_THREADS : 1;
		int jobCount= 1;
		if (maxThreads > 1) {
			jobCount= (files.length + FILES_PER_JOB - 1) / FILES_PER_JOB;
		}
		// Too many job references can cause OOM, see bug 514961
		if (jobCount > MAX_JOBS_COUNT) {
			jobCount= MAX_JOBS_COUNT;
		}

		// Seed count over 1 can cause endless waits, see bug 543629 comment 2
		// TODO use seed = jobCount after the bug 543660 in JobGroup is fixed
		final int seed = 1;
		final JobGroup jobGroup = new TextSearchJobGroup("Text Search", maxThreads, seed); //$NON-NLS-1$
		long startTime= TRACING ? System.currentTimeMillis() : 0;

		Job monitorUpdateJob= new Job(SearchMessages.TextSearchVisitor_progress_updating_job) {
			private int fLastNumberOfScannedFiles= 0;

			@Override
			public IStatus run(IProgressMonitor inner) {
				while (!inner.isCanceled()) {
					// Propagate user cancellation to the JobGroup.
					if (fProgressMonitor.isCanceled()) {
						jobGroup.cancel();
						break;
					}

					IFile file;
					int numberOfScannedFiles;
					synchronized (fLock) {
						file= fCurrentFile;
						numberOfScannedFiles= fNumberOfScannedFiles;
					}
					if (file != null) {
						String fileName= file.getName();
						Object[] args= { fileName, Integer.valueOf(numberOfScannedFiles), Integer.valueOf(fNumberOfFilesToScan)};
						fProgressMonitor.subTask(Messages.format(SearchMessages.TextSearchVisitor_scanning, args));
						int steps= numberOfScannedFiles - fLastNumberOfScannedFiles;
						fProgressMonitor.worked(steps);
						fLastNumberOfScannedFiles += steps;
					}
					try {
						Thread.sleep(100);
					} catch (InterruptedException e) {
						return Status.OK_STATUS;
					}
				}
				return Status.OK_STATUS;
			}
		};

		try {
			String taskName= fSearchPattern.pattern().isEmpty()
					? SearchMessages.TextSearchVisitor_filesearch_task_label
					: Messages.format(SearchMessages.TextSearchVisitor_textsearch_task_label, fSearchPattern.pattern());
			fProgressMonitor.beginTask(taskName, fNumberOfFilesToScan);
			monitorUpdateJob.setSystem(true);
			monitorUpdateJob.schedule();
			try {
				fCollector.beginReporting();
				Map<IFile, IDocument> documentsInEditors= PlatformUI.isWorkbenchRunning() ? evalNonFileBufferDocuments() : Collections.emptyMap();
				int filesPerJob = Math.max(1, files.length / jobCount);
				// Sorting files to search by location allows to more easily reuse
				// search results from one file to the other when they have same location
				IFile[] filesByLocation = Arrays.stream(files).map(FileWithCachedLocation::new)
						.sorted(FileWithCachedLocation.BY_LOCATION)
						.map(FileWithCachedLocation::getFile)
						.collect(Collectors.toList()).toArray(IFile[]::new);
				for (int first = 0; first < filesByLocation.length; first += filesPerJob) {
					int end= Math.min(filesByLocation.length, first + filesPerJob);
					Job job= new TextSearchJob(filesByLocation, first, end, documentsInEditors);
					job.setJobGroup(jobGroup);
					job.schedule();
				}

				// The monitorUpdateJob is managing progress and cancellation,
				// so it is ok to pass a null monitor into the job group.
				jobGroup.join(0, null);
				if (fProgressMonitor.isCanceled())
					throw new OperationCanceledException(SearchMessages.TextSearchVisitor_canceled);

				fStatus.addAll(jobGroup.getResult());
				return fStatus;
			} catch (InterruptedException e) {
				throw new OperationCanceledException(SearchMessages.TextSearchVisitor_canceled);
			} finally {
				monitorUpdateJob.cancel();
			}
		} finally {
			fProgressMonitor.done();
			fCollector.endReporting();
			if (TRACING) {
				Object[] args= { Integer.valueOf(fNumberOfScannedFiles), Integer.valueOf(jobCount), Integer.valueOf(NUMBER_OF_LOGICAL_THREADS), Long.valueOf(System.currentTimeMillis() - startTime) };
				System.out.println(Messages.format(
						"[TextSearch] Search duration for {0} files in {1} jobs using {2} threads: {3}ms", args)); //$NON-NLS-1$
			}
		}
	}

	public IStatus search(TextSearchScope scope, IProgressMonitor monitor) {
		return search(scope.evaluateFilesInScope(fStatus), monitor);
	}

	/**
	 * Returns a map from IFile to IDocument for all open, dirty editors. After creation this map
	 * is not modified, so returning a non-synchronized map is ok.
	 *
	 * @return a map from IFile to IDocument for all open, dirty editors
	 */
	private Map<IFile, IDocument> evalNonFileBufferDocuments() {
		Map<IFile, IDocument> result= new HashMap<>();
		IWorkbench workbench = PlatformUI.getWorkbench();
		IWorkbenchWindow[] windows= workbench.getWorkbenchWindows();
		for (IWorkbenchWindow window : windows) {
			IWorkbenchPage[] pages= window.getPages();
			for (IWorkbenchPage page : pages) {
				IEditorReference[] editorRefs= page.getEditorReferences();
				for (IEditorReference editorRef : editorRefs) {
					IEditorPart ep= editorRef.getEditor(false);
					if (ep instanceof ITextEditor && ep.isDirty()) { // only dirty editors
						evaluateTextEditor(result, ep);
					}
				}
			}
		}
		return result;
	}

	private void evaluateTextEditor(Map<IFile, IDocument> result, IEditorPart ep) {
		IEditorInput input= ep.getEditorInput();
		if (input instanceof IFileEditorInput) {
			IFile file= ((IFileEditorInput) input).getFile();
			if (!result.containsKey(file)) { // take the first editor found
				ITextFileBufferManager bufferManager= FileBuffers.getTextFileBufferManager();
				ITextFileBuffer textFileBuffer= bufferManager.getTextFileBuffer(file.getFullPath(), LocationKind.IFILE);
				if (textFileBuffer != null) {
					// file buffer has precedence
					result.put(file, textFileBuffer.getDocument());
				} else {
					// use document provider
					IDocument document= ((ITextEditor) ep).getDocumentProvider().getDocument(input);
					if (document != null) {
						result.put(file, document);
					}
				}
			}
		}
	}

	private boolean hasBinaryContent(CharSequence seq, IFile file) throws CoreException {
		IContentDescription desc= file.getContentDescription();
		if (desc != null) {
			IContentType contentType= desc.getContentType();
			if (contentType != null && contentType.isKindOf(Platform.getContentTypeManager().getContentType(IContentTypeManager.CT_TEXT))) {
				return false;
			}
		}

		// avoid calling seq.length() at it runs through the complete file,
		// thus it would do so for all binary files.
		try {
			int limit= FileCharSequenceProvider.BUFFER_SIZE;
			for (int i= 0; i < limit; i++) {
				if (seq.charAt(i) == '\0') {
					return true;
				}
			}
		} catch (IndexOutOfBoundsException e) {
		} catch (FileCharSequenceException ex) {
			if (ex.getCause() instanceof CharConversionException)
				return true;
			throw ex;
		}
		return false;
	}

	private List<TextSearchMatchAccess> locateMatches(IFile file, CharSequence searchInput, Matcher matcher, IProgressMonitor monitor) throws CoreException {
		List<TextSearchMatchAccess> occurences= null;
		if (searchInput.length() < MAX_BUFFER_LENGTH) {
			// cache the sequence in a single array
			String converted = searchInput.toString();
			if (converted.length() != searchInput.length()) {
				throw new CoreException(Status.error( //
						searchInput.getClass().getName() + " does not proper implement CharSequence.toString()", //$NON-NLS-1$
						new IllegalArgumentException("wrong length"))); //$NON-NLS-1$
			}
			searchInput = converted;
		}
		matcher.reset(searchInput);
		int k= 0;
		while (matcher.find()) {
			if (occurences == null) {
				occurences= new ArrayList<>();
			}
			int start= matcher.start();
			int end= matcher.end();
			if (end != start) { // don't report 0-length matches
				ReusableMatchAccess access= new ReusableMatchAccess();
				access.initialize(file, start, end - start, searchInput);
				occurences.add(access);
				boolean res= fCollector.acceptPatternMatch(access);
				if (!res) {
					return occurences; // no further reporting requested
				}
			}
			// Periodically check for cancellation and quit working on the current file if the job has been cancelled.
			if (++k % 20 == 0 && monitor.isCanceled()) {
				break;
			}
		}
		if (occurences == null) {
			occurences= Collections.emptyList();
		}
		return occurences;
	}


	private String getExceptionMessage(Exception e) {
		String message= e.getLocalizedMessage();
		if (message == null) {
			return e.getClass().getName();
		}
		return message;
	}

	private IDocument getOpenDocument(IFile file, Map<IFile, IDocument> documentsInEditors) {
		IDocument document= documentsInEditors.get(file);
		if (document == null) {
			ITextFileBufferManager bufferManager= FileBuffers.getTextFileBufferManager();
			ITextFileBuffer textFileBuffer= bufferManager.getTextFileBuffer(file.getFullPath(), LocationKind.IFILE);
			if (textFileBuffer != null) {
				document= textFileBuffer.getDocument();
			}
		}
		return document;
	}

	private String getCharSetName(IFile file) {
		try {
			return file.getCharset();
		} catch (CoreException e) {
			return "unknown"; //$NON-NLS-1$
		}
	}

}

