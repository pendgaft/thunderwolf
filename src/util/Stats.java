package util;

import java.io.*;
import java.util.*;

public class Stats {

	public static double mean(List<Integer> vals){
		double sum = 0;
		for(int tVal: vals){
			sum += tVal;
		}
		return sum / ((double)vals.size());
	}
	
	public static double median(List<Integer> vals){
		Collections.sort(vals);
		if(vals.size() % 2 == 0){
			double a = vals.get(vals.size() / 2);
			double b = vals.get((vals.size() / 2) - 1);
			return (a + b)/2;
		}
		else{
			int pos = vals.size() / 2;
			return vals.get(pos);
		}
	}
	
	public static double stdDev(List<Integer> vals){
		double mean = Stats.mean(vals);
		
		double sum = 0;
		for(int tVal: vals){
			double tDoub = (double)tVal;
			sum += Math.pow((tDoub - mean), 2);
		}
		
		return Math.sqrt(sum / ((double)vals.size()));
	}
	
	public static void printCDF(List<Double> vals, String fileName) throws IOException{
		Collections.sort(vals);
		double fracStep = 1.0 / (double)vals.size();
		double currStep = 0.0;
		
		BufferedWriter outFile = new BufferedWriter(new FileWriter(fileName));
		
		for(int counter = 0; counter < vals.size(); counter++){
			currStep += fracStep;
			outFile.write("" + currStep + "," + vals.get(counter) + "\n");
		}
		
		outFile.close();		
	}
}
