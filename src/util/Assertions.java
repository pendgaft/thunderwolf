package util;

import java.util.*;
import java.io.*;

/**
 * Homebrew unit testing class. Yes, I should just learn and use JUnit, but
 * right now I *think* it will be faster just to write this on my own.
 * 
 * @author pendgaft
 * 
 */
public class Assertions {

	private int totalTestsRan;
	private int totalTestsPassed;
	private int totalTestsFailed;

	private List<String> failureMessages;

	/**
	 * Question: who tests, the tester. Answer: this guy.
	 * 
	 * @param args
	 */
	public static void main(String[] args) {
		Assertions me = new Assertions();

		me.assertEqual(1, 1, "truth");
		me.assertEqual(1, 2, "false");
		me.assertEqual("hi", "hi", "truth");
		me.assertEqual("hi", "hi ", "too many spaces");

		List<Integer> a = new LinkedList<Integer>();
		List<Integer> b = new LinkedList<Integer>();

		a.add(3);
		a.add(-4);
		b.add(3);
		b.add(-4);

		Integer[] tmp = new Integer[0];
		me.assertEqualInOrder(a.toArray(tmp), b.toArray(tmp), "truth");
		a.add(5);
		me.assertEqualInOrder(a.toArray(tmp), b.toArray(tmp), "false");
		b.add(5);
		me.assertEqualInOrder(a.toArray(tmp), b.toArray(tmp), "truth");
		a.add(324);
		b.add(5);
		me.assertEqualInOrder(a.toArray(tmp), b.toArray(tmp), "false");

		List<String> as = new LinkedList<String>();
		List<String> bs = new LinkedList<String>();

		as.add("Hi");
		as.add("foo");
		bs.add("Hi");
		bs.add("foo");

		String[] tmps = new String[0];
		me.assertEqualInOrder(as.toArray(tmps), bs.toArray(tmps), "truth");
		as.add("bar");
		me.assertEqualInOrder(as.toArray(tmps), bs.toArray(tmps), "false");
		bs.add("bar");
		me.assertEqualInOrder(as.toArray(tmps), bs.toArray(tmps), "truth");
		as.add("later");
		bs.add("bye");
		me.assertEqualInOrder(as.toArray(tmps), bs.toArray(tmps), "false");

		System.out.println("***  Currently should be 6 pass 6 fail.  ***");
		me.printReport(true, System.out);
	}

	/**
	 * Creates an Assertions class to do basically the same job as a JUnit test.
	 * A set of test assertions should be handed to this class, and then a
	 * report can be generated. You can recycle this object for multiple test
	 * sets using the reset option if you truly want.
	 */
	public Assertions() {
		this.totalTestsRan = 0;
		this.totalTestsPassed = 0;
		this.totalTestsFailed = 0;

		this.failureMessages = new ArrayList<String>();
	}

	/**
	 * Resets testing record.
	 */
	public void reset() {
		this.totalTestsRan = 0;
		this.totalTestsPassed = 0;
		this.totalTestsFailed = 0;
		this.failureMessages.clear();
	}

	/**
	 * Prints the results of all tests to date. Passed tests are simply counted,
	 * error messages from failed tests can be optionally printed out.
	 * 
	 * @param printFailureStrings
	 *            - set to true if you want the error messages from failures,
	 *            false if you only want a count of success vs failure
	 * @param output
	 *            - PrintStream where you want the results to go, a good simple
	 *            choice is of course System.out
	 */
	public void printReport(boolean printFailureStrings, PrintStream output) {
		/*
		 * Print out the basic stats
		 */
		output.println("Assertions ran: " + this.totalTestsRan);
		output.println("Assertions passed: "
				+ this.totalTestsPassed
				+ " ("
				+ Math.round((float) this.totalTestsPassed
						/ (float) this.totalTestsRan * 100.0) + "%)");
		output.println("Assertions failed: "
				+ this.totalTestsFailed
				+ " ("
				+ Math.round((float) this.totalTestsFailed
						/ (float) this.totalTestsRan * 100.0) + "%)");

		/*
		 * If we're suppose to, dump the failure strings, if there are none then
		 * don't even bother w/ headers
		 */
		if (printFailureStrings && this.totalTestsFailed > 0) {
			output.println(" ");
			output.println("Failure Reports");
			output.println("***************");

			for (String tMsg : this.failureMessages) {
				output.println(tMsg);
			}
		}
	}

	/**
	 * Assertion test that two strings are equal. This IS case and white space
	 * sensitive.
	 * 
	 * @param value
	 *            - the value to be tested
	 * @param truth
	 *            - the known truth
	 * @param errMsg
	 *            - an optional string to be added to an error message to note
	 *            what this test was for, can be null if you don't want one
	 */
	public void assertEqual(String value, String truth, String errMsg) {
		this.totalTestsRan++;
		if (truth.equals(value)) {
			this.totalTestsPassed++;
		} else {
			this.totalTestsFailed++;
			this.failureMessages.add(this.buildErrMessage(
					"String assert equal failed", value, truth, errMsg));
		}
	}

	/**
	 * Assertion test that two ints are equal.
	 * 
	 * @param value
	 *            - the value to be tested
	 * @param truth
	 *            - the known truth
	 * @param errMsg
	 *            - an optional string to be added to an error message to note
	 *            what this test was for, can be null if you don't want one
	 */
	public void assertEqual(int value, int truth, String errMsg) {
		this.totalTestsRan++;
		if (truth == value) {
			this.totalTestsPassed++;
		} else {
			this.totalTestsFailed++;
			this.failureMessages.add(this.buildErrMessage(
					"Integer assert equal failed", value, truth, errMsg));
		}
	}

	/**
	 * Assertion test that two lists of strings are equal. This IS case and
	 * white space sensitive. The strings must appear in the same order, and all
	 * strings in the ground truth must be present in the test value.
	 * 
	 * @param value
	 *            - the value to be tested
	 * @param truth
	 *            - the known truth
	 * @param errMsg
	 *            - an optional string to be added to an error message to note
	 *            what this test was for, can be null if you don't want one
	 */
	public void assertEqualInOrder(String[] value, String[] truth, String errMsg) {
		this.totalTestsRan++;

		if (value.length != truth.length) {
			this.totalTestsFailed++;
			this.failureMessages.add(this.buildErrMessage(
					"In-order String array assert equal failed", value, truth,
					errMsg));
			return;
		}

		for (int counter = 0; counter < value.length; counter++) {
			if (!value[counter].equals(truth[counter])) {
				this.totalTestsFailed++;
				this.failureMessages.add(this.buildErrMessage(
						"In-order String array assert equal failed", value,
						truth, errMsg));
				return;
			}
		}

		this.totalTestsPassed++;
	}

	/**
	 * Assertion test that two integers of strings are equal. The ints must
	 * appear in the same order, and all ints in the ground truth must be
	 * present in the test value.
	 * 
	 * @param value
	 *            - the value to be tested
	 * @param truth
	 *            - the known truth
	 * @param errMsg
	 *            - an optional string to be added to an error message to note
	 *            what this test was for, can be null if you don't want one
	 */
	public void assertEqualInOrder(Integer[] value, Integer[] truth,
			String errMsg) {
		this.totalTestsRan++;

		if (value.length != truth.length) {
			this.totalTestsFailed++;
			this.failureMessages.add(this.buildErrMessage(
					"In-order Integer array assert equal failed", value, truth,
					errMsg));
			return;
		}

		for (int counter = 0; counter < value.length; counter++) {
			if (value[counter] != truth[counter]) {
				this.totalTestsFailed++;
				this.failureMessages.add(this.buildErrMessage(
						"In-order Integer array assert equal failed", value,
						truth, errMsg));
				return;
			}
		}

		this.totalTestsPassed++;
	}

	/**
	 * Helper method to build an error message when an assertion fails. Done to
	 * use StringBuilders and make code less clusterfuck.
	 * 
	 * @param baseStr
	 *            - the error message from the assert method, should tell what
	 *            the assertion type was
	 * @param value
	 *            - the tested value
	 * @param truth
	 *            - the ground truth
	 * @param userErrMsg
	 *            - an optional error message passed from the user, can be null
	 *            if the user doesn't want to add a custom logging message
	 * @return a nicely formated error string for the error log
	 */
	private String buildErrMessage(String baseStr, Object value, Object truth,
			String userErrMsg) {
		StringBuilder strBuild = new StringBuilder();
		strBuild.append(baseStr);
		strBuild.append(" Value: ");
		strBuild.append(value);
		strBuild.append(" Truth: ");
		strBuild.append(truth);

		if (userErrMsg != null) {
			strBuild.append(" (");
			strBuild.append(userErrMsg);
			strBuild.append(")");
		}

		return strBuild.toString();
	}

	/**
	 * Helper function to convert a generic list of ints into an Integer array.
	 * This wraps Java's anal-retentive type conventions.
	 * 
	 * @param intList
	 *            - a list of integers
	 * @return an array containing the integers in the given list
	 */
	public static Integer[] arrayHelperInt(List<Integer> intList) {
		Integer[] tmp = new Integer[0];
		return intList.toArray(tmp);
	}

	/**
	 * Helper function to covert a generic list of strings into a String array.
	 * Again, this wraps Java's anal-retentive type conventions.
	 * 
	 * @param strList
	 *            - a list of Strings
	 * @return an array containing the Strings in the given list
	 */
	public static String[] arrayHelperString(List<String> strList) {
		String[] temp = new String[0];
		return strList.toArray(temp);
	}
}
