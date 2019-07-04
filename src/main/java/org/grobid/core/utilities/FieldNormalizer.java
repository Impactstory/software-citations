package org.grobid.core.utilities;


import org.apache.http.HttpResponse;
import org.apache.http.NameValuePair;
import org.apache.http.client.HttpClient;
import org.apache.http.client.entity.UrlEncodedFormEntity;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.impl.client.DefaultHttpClient;
import org.apache.http.message.BasicNameValuePair;

/*import com.sun.jersey.api.client.WebResource;
import com.sun.jersey.api.client.config.ClientConfig;
import com.sun.jersey.api.client.config.DefaultClientConfig;*/

import com.fasterxml.jackson.databind.*;

import java.nio.charset.StandardCharsets;
import java.io.*;
import java.util.regex.*;
import java.net.URL;
import org.xml.sax.*;
import org.xml.sax.helpers.*;
import javax.xml.parsers.*;
import java.net.URLDecoder;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.grobid.core.utilities.KeyGen;
import org.grobid.core.utilities.TextUtilities;

/**
 *  A set of static methods for normalizing fields. Manual annotations often appear inconsistent
 *  and we try here to regularize them.   
 */
public class FieldNormalizer {

    /**
     * The main issue with version number is the inclusion or not of the term "version" (and all its variants)
     * into the field or not. As this term is not an interesting variable part, we excluse it from the annotation
     * to get fully consistent annotation. 
     */
    public static String normalizeVersionNumber(String versionNumber) {
        // basic usual cleaning
        versionNumber = versionNumber.replace("\n", " ");
        versionNumber = versionNumber.replaceAll("( )+", " ");
        versionNumber = versionNumber.trim();

        // these regex and cleaning will cover all the cases seen in the training data
        
        // leading and trailing parenthesis and similar
        versionNumber = removeLeadingAndTrailing(versionNumber, "()[]");

        versionNumber = versionPattern1.matcher(versionNumber).replaceAll("");
        versionNumber = versionPattern2.matcher(versionNumber).replaceAll("");

        versionNumber = removeLeadingAndTrailing(versionNumber, ".");

        // final usual cleaning
        versionNumber = versionNumber.replaceAll("( )+", " ");
        versionNumber = versionNumber.trim();

        return versionNumber;
    }

    static public final Pattern versionPattern1 = Pattern.compile("^(ver(\\-)?sion(s)?|ver|v|release(s)?|build)\\s?\\.?\\s?", Pattern.CASE_INSENSITIVE);
    static public final Pattern versionPattern2 = Pattern.compile("(version(s)?|ver|release(s)?)$", Pattern.CASE_INSENSITIVE);

    public static String normalizeUrl(String url) {
        url = url.replace("\n", " ");
        url = url.replaceAll("( )+", " ");
        url = url.trim();
        
        // leading and trailing parenthesis and similar
        url = removeLeadingAndTrailing(url, "()[],;.’“\"");

        // now grab the url by standard url pattern
        Matcher matcher = urlPattern.matcher(url);
        // Check all occurrences
        while (matcher.find()) {
            //System.out.print("Start index: " + matcher.start());
            //System.out.print(" End index: " + matcher.end());
            url = url.substring(matcher.start(), matcher.end());
            url = url.replaceAll("( )+", "");
        }

        return url;
    }

    static public final Pattern urlPattern = Pattern
        .compile("(?i)(https?|ftp)\\s?:\\s?//\\s?[-A-Z0-9+&@#/%?=~_()|!:,.;\\s]*[-A-Z0-9+&@#/%=~_()|]");

    public static String normalizeCreator(String creator) {
        creator = creator.replace("\n", " ");
        creator = creator.replaceAll("( )+", " ");
        creator = creator.trim();
        
        // leading and trailing parenthesis and similar
        creator = removeLeadingAndTrailing(creator, "()[],;.’“\"");
        
        // removing extra address
        Matcher matcher = companyPattern.matcher(creator);
        // Check all occurrences
        while (matcher.find()) {
            //System.out.print(" End index: " + matcher.end());
            creator = creator.substring(0, matcher.end());
            creator = creator.replaceAll("( )+", " ");
        }

        return creator.trim();
    }    

    static public final Pattern companyPattern = Pattern
        .compile("(incorporated|inc|corporation|corp|ltd)\\s?\\.?", Pattern.CASE_INSENSITIVE);


    public static String removeLeadingAndTrailing(String s, String toRemove) {
        s = removeLeading(s, toRemove);
        s = removeTrailing(s, toRemove);
        return s;
    }

    public static String removeLeading(String s, String toRemove) {
        StringBuilder sb = new StringBuilder(s);
        while (sb.length() > 0 && toRemove.indexOf(sb.charAt(0)) != -1) {
            sb.deleteCharAt(0);
        }
        return sb.toString();
    }
 
    public static String removeTrailing(String s, String toRemove) {
        StringBuilder sb = new StringBuilder(s);
        while (sb.length() > 0 && toRemove.indexOf(sb.charAt(sb.length() - 1)) != -1) {
            sb.setLength(sb.length() - 1);
        }
        return sb.toString();
    }


}