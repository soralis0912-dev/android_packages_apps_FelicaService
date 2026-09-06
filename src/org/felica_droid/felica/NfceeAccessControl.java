/*
 * SPDX-FileCopyrightText: 2026 The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.felica_droid.felica;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.content.pm.SigningInfo;
import android.util.Log;
import android.util.Xml;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.io.Reader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Decides who may reach the secure element.
 *
 * AOSP had a class by this name until the NFC extras API was dropped; this is a
 * fresh one against the same file, /product/etc/felica_access.xml, which FeliCa
 * Networks ships alongside the client. Its shape is
 *
 *   <signer android:signature="<hex DER cert>">
 *     <package android:name="com.felicanetworks.mfc" />
 *   </signer>
 *
 * and the rule is both halves: the caller has to be one of the named packages
 * and be signed by that signer. A package name on its own proves nothing, since
 * the caller hands it to us.
 *
 * There is no exemption for the platform signature. The client ships PRESIGNED
 * and the certificate the stock file names is byte for byte the one on that
 * apk, so the ordinary path admits it; anything that needed an exemption would
 * be something FeliCa Networks did not vouch for.
 */
public class NfceeAccessControl {

    private static final String TAG = "NfceeAccessControl";
    private static final String ACCESS_FILE = "/product/etc/felica_access.xml";
    private static final String ANDROID_NS = "http://schemas.android.com/apk/res/android";

    private final Context mContext;

    /** signature (as a Signature) -> the package names it vouches for. */
    private final Map<Signature, List<String>> mRules = new HashMap<>();
    private final boolean mParsed;

    public NfceeAccessControl(Context context) {
        mContext = context;
        mParsed = parse(new File(ACCESS_FILE));
    }

    /**
     * @param uid the caller, from Binder.getCallingUid()
     * @param pkg the package the caller says it is
     */
    public boolean check(int uid, String pkg) {
        if (!mParsed || pkg == null) {
            return false;
        }

        // The caller does not get to name a package that is not its own.
        String[] owned = mContext.getPackageManager().getPackagesForUid(uid);
        if (owned == null || !Arrays.asList(owned).contains(pkg)) {
            Log.w(TAG, "uid " + uid + " does not own " + pkg);
            return false;
        }

        Signature[] signatures = signaturesOf(pkg);
        if (signatures == null) {
            return false;
        }

        for (Signature signature : signatures) {
            List<String> packages = mRules.get(signature);
            if (packages != null && packages.contains(pkg)) {
                return true;
            }
        }
        return false;
    }

    private Signature[] signaturesOf(String pkg) {
        try {
            PackageInfo info = mContext.getPackageManager().getPackageInfo(
                    pkg, PackageManager.GET_SIGNING_CERTIFICATES);
            SigningInfo signing = info.signingInfo;
            if (signing == null) {
                return null;
            }
            // A rotated key still has to be matched on what it was when the
            // access file was written, so take the whole history.
            return signing.hasMultipleSigners()
                    ? signing.getApkContentsSigners()
                    : signing.getSigningCertificateHistory();
        } catch (PackageManager.NameNotFoundException e) {
            Log.w(TAG, pkg + " is not installed");
            return null;
        }
    }

    private boolean parse(File file) {
        if (!file.exists()) {
            Log.i(TAG, file + " is not present; nothing may reach the element");
            return false;
        }
        try (Reader reader = new FileReader(file)) {
            XmlPullParser parser = Xml.newPullParser();
            parser.setInput(reader);

            Signature signer = null;
            List<String> packages = null;

            int event;
            while ((event = parser.next()) != XmlPullParser.END_DOCUMENT) {
                if (event == XmlPullParser.START_TAG) {
                    if ("signer".equals(parser.getName())) {
                        String hex = parser.getAttributeValue(ANDROID_NS, "signature");
                        signer = hex == null ? null : new Signature(hex);
                        packages = new ArrayList<>();
                    } else if ("package".equals(parser.getName()) && packages != null) {
                        String name = parser.getAttributeValue(ANDROID_NS, "name");
                        if (name != null) {
                            packages.add(name);
                        }
                    }
                } else if (event == XmlPullParser.END_TAG
                        && "signer".equals(parser.getName())) {
                    if (signer != null && packages != null && !packages.isEmpty()) {
                        mRules.computeIfAbsent(signer, k -> new ArrayList<>()).addAll(packages);
                    }
                    signer = null;
                    packages = null;
                }
            }
        } catch (IOException | XmlPullParserException | IllegalArgumentException e) {
            Log.e(TAG, "could not read " + file, e);
            return false;
        }
        Log.i(TAG, "loaded " + mRules.size() + " signer(s) from " + file);
        return !mRules.isEmpty();
    }
}
