package util;

import java.io.*;
import java.util.*;

/**
 * This class contains a few useful stats functions.
 * 
 * @author pendgaft
 * 
 */
public class Stats {

	/**
	 * Method that computes the mean of a list of integers.
	 * 
	 * @param vals
	 *            - a non-empty list of integers
	 * @return - the mean of the values in a double
	 */
	public static double mean(List<Long> vals) {
		/*
		 * div zero errors are bad...
		 */
		if (vals.size() == 0) {
			throw new RuntimeException("Asked to compute the mean of an empty list!");
		}

		double sum = 0;
		for (long tVal : vals) {
			sum += tVal;
		}
		return sum / ((double) vals.size());
	}

	/**
	 * Method that computes the median of a list of integers.
	 * 
	 * @param vals
	 *            - a non-empty list of ints
	 * @return the median of the supplied list
	 */
	public static double median(List<Long> vals) {
		/*
		 * median on empty lists, that a nono..
		 */
		if (vals.size() == 0) {
			throw new RuntimeException("Asked to compute the median of an empty list!");
		}

		/*
		 * create a clone of the list, since sorting of the original list would
		 * be a side effect of this method and we want to avoid that.
		 */
		List<Long> sortedList = new ArrayList<Long>();
		for (long tInt: vals) {
			sortedList.add(tInt);
		}

		/*
		 * Actually compute the median
		 */
		Collections.sort(sortedList);
		if (sortedList.size() % 2 == 0) {
			double a = sortedList.get(sortedList.size() / 2);
			double b = sortedList.get((sortedList.size() / 2) - 1);
			return (a + b) / 2;
		} else {
			int pos = sortedList.size() / 2;
			return sortedList.get(pos);
		}
	}
	
	public static long max(List<Long> vals){
		/*
		 * empty list makes no sense...
		 */
		if (vals.size() == 0) {
			throw new RuntimeException("Asked to compute the max of an empty list!");
		}
		
		long maxVal = Long.MIN_VALUE;
		for(long tVal: vals){
			if(tVal > maxVal){
				maxVal = tVal;
			}
		}
		
		return maxVal;
	}

	/**
	 * Method that computes the standard deviation of a list of integer.s
	 * 
	 * @param vals
	 *            - a non-empty list of ints to compute over
	 * @return - the std deviation of the supplied list
	 */
	public static double stdDev(List<Long> vals) {
		/*
		 * div zero errors are bad...
		 */
		if (vals.size() == 0) {
			throw new RuntimeException("Asked to compute the std dev of an empty list!");
		}

		double mean = Stats.mean(vals);

		double sum = 0;
		for (long tVal : vals) {
			double tDoub = (double) tVal;
			sum += Math.pow((tDoub - mean), 2);
		}

		return Math.sqrt(sum / ((double) vals.size()));
	}

	/**
	 * Function that dumps a CDF of the supplied list of doubles to a file
	 * specified by a string.
	 * 
	 * @param vals
	 *            - a non-empty list of doubles
	 * @param fileName
	 *            - the file the CDF will be writen to in CSV format
	 * @throws IOException
	 *             - if there is an error writting to the file matching the file
	 *             name
	 */
	public static void printCDF(List<Double> origVals, String fileName) throws IOException {
		/*
		 * CDFs over empty lists don't really make sense
		 */
		if (origVals.size() == 0) {
			throw new RuntimeException("Asked to build CDF of an empty list!");
		}

		/*
		 * Clone the list to avoid the side effect of sorting the original list
		 */
		List<Double> vals = new ArrayList<Double>(origVals.size());
		for (double tDouble : origVals) {
			vals.add(tDouble);
		}

		Collections.sort(vals);
		double fracStep = 1.0 / (double) vals.size();
		double currStep = 0.0;

		BufferedWriter outFile = new BufferedWriter(new FileWriter(fileName));

		for (int counter = 0; counter < vals.size(); counter++) {
			currStep += fracStep;
			if (counter >= vals.size() - 1 || vals.get(counter) != vals.get(counter + 1)) {
				outFile.write("" + currStep + "," + vals.get(counter) + "\n");
			}
		}

		outFile.close();
	}
}
