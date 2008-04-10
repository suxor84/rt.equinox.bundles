/*******************************************************************************
 * Copyright (c) 2008 IBM Corporation and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 * 
 * Contributors:
 *     IBM Corporation - initial API and implementation
 *******************************************************************************/
package org.eclipse.equinox.internal.security.storage;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import javax.crypto.BadPaddingException;
import javax.crypto.IllegalBlockSizeException;
import javax.crypto.spec.PBEKeySpec;
import org.eclipse.equinox.internal.security.auth.AuthPlugin;
import org.eclipse.equinox.internal.security.auth.nls.SecAuthMessages;
import org.eclipse.equinox.security.storage.EncodingUtils;
import org.eclipse.equinox.security.storage.StorageException;
import org.eclipse.equinox.security.storage.provider.IPreferencesContainer;
import org.eclipse.equinox.security.storage.provider.IProviderHints;
import org.eclipse.osgi.util.NLS;

public class PasswordManagement {

	/**
	 * Algorithm used to digest passwords
	 */
	private static final String DIGEST_ALGORITHM = "MD5"; //$NON-NLS-1$

	/**
	 * Node used to store encrypted password for the password recovery
	 */
	private final static String PASSWORD_RECOVERY_NODE = "/org.eclipse.equinox.secure.storage/recovery"; //$NON-NLS-1$

	/**
	 * Pseudo-module ID to use when encryption is done with the default password.
	 */
	protected final static String RECOVERY_PSEUDO_ID = "org.eclipse.equinox.security.recoveryModule"; //$NON-NLS-1$

	/**
	 * Key used to store encrypted password for the password recovery
	 */
	private final static String PASSWORD_RECOVERY_KEY = "org.eclipse.equinox.security.internal.recovery.password"; //$NON-NLS-1$

	/**
	 * Key used to store questions for the password recovery
	 */
	private final static String PASSWORD_RECOVERY_QUESTION = "org.eclipse.equinox.security.internal.recovery.question"; //$NON-NLS-1$

	static public void setupRecovery(SecurePreferencesRoot root, PasswordExt passwordExt, IPreferencesContainer container) {
		// check if we are allowed to prompt
		if (!canPrompt(container))
			return;

		// encrypt user password with the mashed-up answers and store encrypted value
		String moduleID = passwordExt.getModuleID();
		SecurePreferences node = recoveryNode(root, moduleID);

		String[][] userParts = CallbacksProvider.getDefault().formChallengeResponse();
		if (userParts == null) {
			node.remove(PASSWORD_RECOVERY_KEY);
			for (int i = 0; i < 2; i++) {
				String key = PASSWORD_RECOVERY_QUESTION + Integer.toString(i + 1);
				node.remove(key);
			}
			root.markModified();
			return;
		}
		// create password from mixing and boiling answers
		String internalPassword = mashPassword(userParts[1]);

		PasswordExt internalPasswordExt = new PasswordExt(new PBEKeySpec(internalPassword.toCharArray()), RECOVERY_PSEUDO_ID);
		try {
			byte[] data = new String(passwordExt.getPassword().getPassword()).getBytes();
			CryptoData encryptedValue = root.getCipher().encrypt(internalPasswordExt, data);
			node.internalPut(PASSWORD_RECOVERY_KEY, encryptedValue.toString());
			root.markModified();
		} catch (StorageException e) {
			AuthPlugin.getDefault().logError(SecAuthMessages.failedCreateRecovery, e);
			return;
		}

		// save questions
		for (int i = 0; i < userParts[0].length; i++) {
			String key = PASSWORD_RECOVERY_QUESTION + Integer.toString(i + 1);
			try {
				node.put(key, userParts[0][i], false, (SecurePreferencesContainer) container);
			} catch (StorageException e) {
				// not going to happen for non-encrypted values
			}
			// already marked as modified
		}
	}

	static public String[] getPasswordRecoveryQuestions(SecurePreferencesRoot root, String moduleID) {
		// retrieve stored questions
		List questions = new ArrayList();
		SecurePreferences node = recoveryNode(root, moduleID);
		for (int i = 0;; i++) {
			String key = PASSWORD_RECOVERY_QUESTION + Integer.toString(i + 1);
			if (!node.hasKey(key))
				break;
			try {
				String question = node.get(key, null, null);
				if (question == null)
					break;
				questions.add(question);
			} catch (StorageException e) {
				// can't happen for non-encrypted values
			}
		}
		String[] result = new String[questions.size()];
		return (String[]) questions.toArray(result);
	}

	static public String recoverPassword(String[] answers, SecurePreferencesRoot root, String moduleID) {
		String internalPassword = mashPassword(answers); // create recovery password from answers

		SecurePreferences node = recoveryNode(root, moduleID);
		PasswordExt internalPasswordExt = new PasswordExt(new PBEKeySpec(internalPassword.toCharArray()), RECOVERY_PSEUDO_ID);
		try {
			CryptoData encryptedData = new CryptoData(node.internalGet(PASSWORD_RECOVERY_KEY));
			byte[] data = root.getCipher().decrypt(internalPasswordExt, encryptedData);
			return new String(data);
		} catch (IllegalStateException e) {
			return null;
		} catch (IllegalBlockSizeException e) {
			return null;
		} catch (BadPaddingException e) {
			return null;
		} catch (StorageException e) {
			return null;
		}
	}

	static private SecurePreferences recoveryNode(SecurePreferences root, String moduleID) {
		return root.node(PASSWORD_RECOVERY_NODE).node(moduleID);
	}

	static private boolean canPrompt(IPreferencesContainer container) {
		if (container.hasOption(IProviderHints.PROMPT_USER)) {
			Object promptHint = container.getOption(IProviderHints.PROMPT_USER);
			if (promptHint instanceof Boolean) {
				return ((Boolean) promptHint).booleanValue();
			}
		}
		return true;
	}

	/**
	 * Produces password from a list of answers:
	 * - all answers are put into one string
	 * - characters from alternating ends of the string are taken to form "mashed up" recovery 
	 * password
	 * - the secure digest of the "mashed up" string is created
	 * 
	 * This procedure should improve quality of the recovery password - even if answers 
	 * are dictionary words, digested "mashed up" password should be of a reasonable good quality 
	 */
	static private String mashPassword(String[] answers) {
		// form a string composing answers
		StringBuffer tmp = new StringBuffer();
		for (int i = 0; i < answers.length; i++) {
			tmp.append(answers[i].trim());
		}
		// mix it up
		StringBuffer mix = new StringBuffer();
		int pos = tmp.length() - 1;
		for (int i = 0; i <= pos; i++) {
			mix.append(tmp.charAt(i));
			if (i < pos)
				mix.append(tmp.charAt(pos));
			pos--;
		}
		// create digest
		String internalPassword;
		try {
			// normally use digest of what was entered
			MessageDigest digest = MessageDigest.getInstance(DIGEST_ALGORITHM);
			byte[] digested = digest.digest(mix.toString().getBytes());
			internalPassword = EncodingUtils.encodeBase64(digested);
		} catch (NoSuchAlgorithmException e) {
			// just use the text as is; it is nicer to use digest but in this case no big deal
			String msg = NLS.bind(SecAuthMessages.noDigest, DIGEST_ALGORITHM);
			AuthPlugin.getDefault().logMessage(msg);
			internalPassword = mix.toString();
		}
		return internalPassword;
	}

}
