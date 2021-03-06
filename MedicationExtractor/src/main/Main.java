package main;

import i2b2.i2b2Integration;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.io.BufferedReader;
import java.io.InputStreamReader;

import org.apache.medex.MedEx;
import org.apache.medex.MedTagger;
import org.apache.uima.resource.ResourceInitializationException;

import drugs.DrugEntry;
import drugs.DrugUtils;
import drugs.DurationFinder;
import drugs.FrequencyFinder;
import drugs.ReasonFinder;
import filter.BadDrugFilter;
import filter.FilterManager;
import medex.MedexResultsParser;
import medxn.MedXNParser;
import merki.MerkiIntegration;
import text.DischargeDocument;
import text.HeaderFinder;
import text.Section;
//import gov.nih.nlm.nls.skr.*
import gov.nih.nlm.nls.skr.*;
import metamap.metaMap;
import ProjectExtension.ProjectExtension;
import ProjectExtension.LengthOfStay;

import java.util.Iterator;


public class Main {
	public static File getResource(String name) {
		
		return new File(Main.class.getResource(name).getFile());
	}		
	
	/**
	 * This function is the main entry point to the program. It takes in a directory for input,
	 * which can have many different discharge summaries. It finds the medication info in each file
	 * and outputs it to the outputDirectory. After execution, the outputDirectory should contain
	 * one file of output for each discharge summary.
	 * @param inputDirectory
	 * @param outputDirectory
	 * @throws IOException 
	 * @throws ResourceInitializationException 
	 */
	
	/*
	 * We take the following steps
	 * 1) Run MedEx on every file in the directory. Results are used in later steps
	 * 2) Iterate through every document, identifying DrugEntries.
	 * 		a) Create a DischargeDocument object
	 * 	 	b) Read in MedEx information for a document
	 * 		c) Supplement MedEx info with MERKI info
	 * 		d) Filter out duplicates between MedEx and MERKI
	 * 		e) Add reason information from MetaMap
	 * 		f) Filter out DrugEntries based on common sources of false-positives
	 * 		g) For each document, write out results to the output directory
	 */
	private static void getResults(File inputDirectory, File outputDirectory) throws IOException, ResourceInitializationException {
		//reads in section lexicons and stores them in memory for the duration of the execution
		
		Section.compileSections(getResource("/resources/sections.txt"),getResource("/resources/badSections.txt"),getResource("/resources/listSections.txt")); //read the sections.txt file to get a list of sections
		BadDrugFilter.compileBadDrugList(getResource("/resources/badDrugs.txt"));
		File goldStandard=getResource("/resources/StudentData/goldStandards/gold.xml");
		File medexOutput=new File(outputDirectory, "medex");
		File finalOutput=new File(outputDirectory, "results");
		File metamapOutput=new File(outputDirectory,"metamap");
		
		HashSet<String> drugNames=new HashSet<String>();
		File medXNOutput=new File(outputDirectory,"cpe/output.out");
		
		finalOutput.mkdirs();
		medexOutput.mkdirs();
		metamapOutput.mkdirs();
		FilterManager.loadFilters();
		i2b2Integration i2b2=new i2b2Integration(getResource("/i2b2").getAbsolutePath(),
				goldStandard.getAbsolutePath(),inputDirectory.getAbsolutePath(), finalOutput.getAbsolutePath());
		
		MerkiIntegration i=new MerkiIntegration(getResource("/merki").getAbsolutePath()); 


		MedTagger m=MedEx.getMedTagger(inputDirectory.getAbsolutePath(), medexOutput.getAbsolutePath());
		m.run_batch_medtag();
		 
        String extensionOutputFileName = outputDirectory+"projectExtension.csv";
        
        ////COMMENT THIS PART OUT AFTER YOU RUN THE TRAINING CASES///
        ProjectExtension partCCreator = new ProjectExtension();
        partCCreator.ProjectExtensionFileCreator(extensionOutputFileName);
        //////////////////////////////////////////////////////////////////

		//store all documents so we can output them at the end
		List<DischargeDocument> docs=new ArrayList<DischargeDocument>();
		for (File f : inputDirectory.listFiles()) {
			if (f.getName().startsWith(".")) {
				continue;
            }
			if (!f.getName().equals("11995")) {
				//continue;
			}
			System.out.println(f.getName());
			DischargeDocument text=new DischargeDocument(f);
			DischargeDocument text2=new DischargeDocument(f);
			DischargeDocument text3=new DischargeDocument(f);
			text3.setFavorDurations(true);
			docs.add(text);
			
			//part b above
			//System.out.println("parsing MedEx results");
			MedexResultsParser.parseMedexResults(text, new File(medexOutput,f.getName()));
			//part c above. Adds all drugs MERKI can find to this element
			//System.out.println("running MERKI");
			//i.runMerki(text3);
			
			//System.out.println("parsing MedXN results");
			//trying to make MedXN supplementary only
			MedXNParser.parseMedXNResults(text2, medXNOutput);
			
			DrugUtils.mergeDrugsInDocuments(text, text2);
			//DrugUtils.mergeDurationsInDocuments(text,text3); //only use MERKI for durations
			//filter out duplicates
			//System.out.println("filtering out duplicates");
			DrugUtils.filterDuplicateDrugs(text);
			
			DurationFinder.findDurations(text);
			FrequencyFinder.findFreqs(text);
			ReasonFinder.findReasons(text);
			
			System.out.println("running MetaMap");
			File G = new File(outputDirectory, "metaMapTemp.txt");
                        metaMap.runMetaMap(text, G);
			
			//step f above. New filters can be defined in the filter package
			//System.out.println("filtering out false positives");
			FilterManager.runAllFilters(text);
			
			
			 //Part C Project Extension//
                        
                        //Get Lenght of Stay//
                        LengthOfStay los = new LengthOfStay();
                        los.findDates(text.getText());//Sets all of the parameters by parsing through the document
                        
                        ProjectExtension partC = new ProjectExtension();
                        partC.setFileName(f.getName());
                        partC.ProjectExtensionParameterizer(text);
                        Integer days = los.returnLOSDays();
                        partC.setLengthOfStay(days);
                        String partCOutput = partC.toString();
                        partC.ProjectExtensionWriter(partCOutput, extensionOutputFileName);
                        //End Part C Project Extension//
			
			
			//System.out.println("printing out results");
			
			File outputFile=new File(finalOutput,(f.getName().replace(".txt", ""))+".i2b2.entries");
			
			for (DrugEntry e : text.getDrugEntries()) {
				if (!e.getDuration().equals("nm")) {
					System.out.println("found duration = "+e.getDuration() +" "+e.getDurationOffset());
				}
				if (!e.getReason().equals("nm")) {
					System.out.println("found reason = "+e.getReason() +" "+e.getReasonOffset());
				}
				drugNames.add(e.getName().toLowerCase().trim().replace("\n", " "));
			}
			
			FileUtils.writeFile(text.getDrugData(), outputFile);
			
			
			
		}
		
		for (String s : drugNames) {
			//System.out.println(s);
		}
		
		i2b2.printResults();
		
	}
	
	

	public static void main(String[] args) throws IOException, ResourceInitializationException {
		ReasonFinder.loadReasons(getResource("/resources/reasons.txt"));
		ReasonFinder.loadBodyParts(getResource("/resources/bodyparts.txt"));

		getResults(getResource("/resources/StudentData/studentTrainingFiles"), 
				new File(getResource("/resources/StudentData"),"output"));
		//System.out.println(ReasonFinder.matchString(ReasonFinder.getLongRegexes(), "I have some jaw pain in my face"));
		/*
		//just some test code running merki on a file
		Section.compileSections(getResource("/resources/sections.txt"),getResource("/resources/badSections.txt")); //read the sections.txt file to get a list of sections
		
		File trainingDir=getResource("/resources/StudentData/studentTrainingFiles");
		
		File outputDirectory=new File("C:/users/eric/desktop/healthOutput");
		MerkiIntegration i=new MerkiIntegration(getResource("/merki").getAbsolutePath());

		for (File f : trainingDir.listFiles()) {
			//this is a temp file
			if (f.getName().startsWith("._")) {
				continue;
			}
			File outputFile=new File(outputDirectory,"output_"+f.getName());
			System.out.println(outputFile.getAbsolutePath());
			FileWriter writer=new FileWriter(outputFile);
			DischargeDocument text=new DischargeDocument(f);
			System.out.println("running MERKI on "+f.getName());
			for (DrugEntry d : i.runMerki(text)) {
				writer.write(d.toString());
				writer.write("\n");
			}
			writer.close();
		}
		*/

		
		
		//the code below just compiles section names. It is no longer needed for the project.
		/*List<File> dirs=new ArrayList<File> ();
		dirs.add(new File("c:/users/eric/desktop/studentdata/studenttrainingfiles"));
		dirs.add(new File("c:/users/eric/desktop/studentdata/AdditionalDischarges-set4"));

		HashSet<String> mySet=HeaderFinder.findHeaders(dirs);
		List<String> names=new ArrayList<String>();

		for (String s : mySet) {
			names.add(s);
		}
		Collections.sort(names);
		
		for (String s : names) {
			System.out.println(s);
		}
		try {
			FileWriter writer=new FileWriter(new File("c:/users/eric/desktop/sections.txt"));
			for (String s : names) {
				writer.write(s);
				writer.write("\n");
				
			}
			writer.close();
		} catch (Exception e) {
			
		}*/
	}
	

}
