package jgi;

import java.io.File;
import java.io.PrintStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.TimeZone;

import align2.BBMap;
import align2.BBSplitter;
import align2.RefToIndex;
import clump.Clumpify;
import dna.Data;
import fileIO.ByteFile1;
import fileIO.ReadWrite;
import fileIO.TextFile;
import fileIO.TextStreamWriter;
import server.ServerTools;
import shared.Parser;
import shared.PreParser;
import shared.Shared;
import shared.Timer;
import shared.Tools;
import shared.TrimRead;
import sketch.SendSketch;
import sketch.SketchObject;
import stream.FASTQ;
import tax.FilterByTaxa;
import tax.GiToNcbi;
import tax.TaxFilter;
import tax.TaxNode;
import tax.TaxTree;

/**
 * Wrapper for several other programs to implement Rolling QC's filter stage.
 * Calls BBDuk, BBMap, BBMerge, and SplitNexteraLMP.
 * Trims adapters, removes contaminants, and does quality-trimming.
 * @author Brian Bushnell
 * @date Nov 26, 2013
 *
 */
public class RQCFilter {

	
	/*--------------------------------------------------------------*/
	/*----------------    Initialization Methods    ----------------*/
	/*--------------------------------------------------------------*/
	
	/**
	 * Program entrance from command line.
	 * @param args Command line arguments
	 */
	public static void main(String[] args){
		//Start a timer
		Timer t=new Timer();
		
		//Create a filter instance
		RQCFilter x=new RQCFilter(args);
		
		//Execute filtering.
		x.process();
		
		//Report time
		t.stop();
		outstream.println("\nOverall Time: \t"+t);
		
		//Close the print stream if it was redirected
		Shared.closeStream(outstream);
	}
	
	/**
	 * Constructor.
	 * @param args Command line arguments
	 */
	RQCFilter(String[] args){
		
		{//Preparse block for help, config files, and outstream
			PreParser pp=new PreParser(args, getClass(), true);
			args=pp.args;
			outstream=pp.outstream;
		}
		
		//Parses some shared arguments
		Parser parser=new Parser();
		
		//Symbols to insert in output filename to denote operations performed; may be overriden from command line
		String symbols_=null;
		
		boolean doNextera_=false;
		FASTQ.DETECT_QUALITY_OUT=false;
		FASTQ.ASCII_OFFSET_OUT=33;
		ReadWrite.USE_PIGZ=ReadWrite.USE_UNPIGZ=true;
		ReadWrite.MAX_ZIP_THREADS=Shared.threads();
		TaxFilter.REQUIRE_PRESENT=false;
		SendSketch.suppressErrors=ServerTools.suppressErrors=true;
		
		ReadWrite.ZIPLEVEL=6;//TODO: is this needed?
		boolean doMerge_=true;
		
		//Parse argument list
		for(int i=0; i<args.length; i++){
			String arg=args[i];
			String[] split=arg.split("="); //Expect key=value pairs
			String a=split[0].toLowerCase(); //All keys are converted to lower case
			String b=split.length>1 ? split[1] : null;
			
			if(Parser.parseCommonStatic(arg, a, b)){
				//do nothing
			}else if(Parser.parseZip(arg, a, b)){
				if(a.equals("pigz")){
					pigz=b;
				}else if(a.equals("unpigz")){
					unpigz=b;
				}else if(a.equals("zl") || a.equals("ziplevel")){
					zl=b;
				}
			}else if(parser.parseInterleaved(arg, a, b)){
				//do nothing
			}else if(a.equals("verbose")){
				verbose=Tools.parseBoolean(b);
				ByteFile1.verbose=verbose;
				stream.FastaReadInputStream.verbose=verbose;
				primaryArgList.add(arg);
			}else if(a.equals("in") || a.equals("input") || a.equals("in1") || a.equals("input1")){
				in1=b;
			}else if(a.equals("in2") || a.equals("input2")){
				in2=b;
			}else if(a.equals("out") || a.equals("output") || a.equals("out1") || a.equals("output1")){
				out1=b;
			}else if(a.equals("out2") || a.equals("output2")){
				out2=b;
			}else if(a.equals("ref")){
				if(b!=null){
					if(!b.contains(",") || new File(b).exists()){
						bbdukFilterRefs.add(b);
					}else{
						String[] split2=b.split(",");
						for(String s2 : split2){
							bbdukFilterRefs.add(s2);
						}
					}
				}
			}else if(a.equals("spikeinref")){
				if(b!=null){
					if(!b.contains(",") || new File(b).exists()){
						spikeinRefs.add(b);
					}else{
						String[] split2=b.split(",");
						for(String s2 : split2){
							spikeinRefs.add(s2);
						}
					}
				}
			}else if(a.equals("artifactdb")){
				mainArtifactFile=b;
			}else if(a.equals("rnadb")){
				artifactFileRna=b;
			}else if(a.equals("dnadb")){
				artifactFileDna=b;
			}else if(a.equals("ribodb")){
				riboKmers=b;
			}else if(a.equals("phixref")){
				phixRef=b;
			}else if(a.equals("fragadapter")){
				fragAdapter=b;
			}else if(a.equals("rnaadapter")){
				rnaAdapter=b;
			}else if(a.equals("lfpelinker")){
				lfpeLinker=b;
			}else if(a.equals("cliplinker") || a.equals("jointseq")){
				clipLinker=b;
			}else if(a.equals("clrslinker")){
				clrsLinker=b;
			}else if(a.equals("bisulfite") || a.equals("bisulfate")){ //Allow for a common mispelling... ;)
				bisulfite=Tools.parseBoolean(b);
			}else if(a.equals("trimfragadapter") || a.equals("trimfragadapters")){
				fragAdapterFlag=Tools.parseBoolean(b);
			}else if(a.equals("trimrnaadapter") || a.equals("trimrnaadapters")){
				rnaAdapterFlag=Tools.parseBoolean(b);
			}else if(a.equals("removehuman") || a.equals("human")){
				humanFlag=Tools.parseBoolean(b);
			}else if(a.equals("removedog") || a.equals("dog")){
				dogFlag=Tools.parseBoolean(b);
			}else if(a.equals("removecat") || a.equals("cat")){
				catFlag=Tools.parseBoolean(b);
			}else if(a.equals("removemouse") || a.equals("mouse")){
				mouseFlag=Tools.parseBoolean(b);
			}else if(a.equals("catdoghuman")){
				catDogHumanFlag=Tools.parseBoolean(b);
			}else if(a.equals("catdoghumanmouse") || a.equals("mousecatdoghuman") || a.equals("catdogmousehuman")){
				mouseCatDogHumanFlag=Tools.parseBoolean(b);
			}else if(a.equals("keephumanreads") || a.equals("keephuman")){
				keepHumanReads=Tools.parseBoolean(b);
			}else if(a.equals("aggressive")){
				aggressiveHumanFlag=aggressiveMicrobeFlag=Tools.parseBoolean(b);
			}else if(a.equals("aggressivehuman")){
				aggressiveHumanFlag=Tools.parseBoolean(b);
			}else if(a.equals("aggressivemicrobe")){
				aggressiveMicrobeFlag=Tools.parseBoolean(b);
			}else if(a.equals("removemicrobes") || a.equals("removecommonmicrobes") || a.equals("microbes")){
				commonMicrobeFlag=Tools.parseBoolean(b);
			}else if(a.equals("ribomap")){
				riboMapFlag=Tools.parseBoolean(b);
			}else if(a.equals("removechloroplast") || a.equals("chloroplast") || a.equals("chloro") || a.equals("chloromap")){
				chloroMapFlag=Tools.parseBoolean(b);
			}else if(a.equals("removemito") || a.equals("mito") || a.equals("mitomap")){
				mitoMapFlag=Tools.parseBoolean(b);
			}else if(a.equals("detectmicrobes") || a.equals("detectcommonmicrobes")){
				detectMicrobeFlag=Tools.parseBoolean(b);
			}else if(a.equals("removeribo") || a.equals("ribo")){
				riboKmerFlag=Tools.parseBoolean(b);
			}else if(a.equals("riboout") || a.equals("outribo")){
				riboOutFile=b;
			}else if(a.equals("overwrite") || a.equals("ow")){
				overwrite=Tools.parseBoolean(b);
			}else if(a.equals("ml") || a.equals("minlen") || a.equals("minlength")){
				minLen=Integer.parseInt(b);
			}else if(a.equals("mlf") || a.equals("minlenfrac") || a.equals("minlenfraction") || a.equals("minlengthfraction")){
				minLenFraction=Float.parseFloat(b);
			}else if(a.equals("libtype") || a.equals("library")){
				libType=toLibType(b);
			}else if(a.equals("path") || a.equals("outdir")){
				outDir=b;
			}else if(a.equals("symbols")){
				symbols_=b;
			}else if(a.equals("overallstats") || a.equals("stats")){
				rqcStatsName=b;
			}else if(a.equals("scafstats")){
				scaffoldStatsName1=b;
			}else if(a.equals("scafstatskt") || a.equals("scafstatstrim")){
				scaffoldStatsName_kt=b;
			}else if(a.equals("refstats")){
				refStatsName=b;
			}else if(a.equals("kmerstats")){
				kmerStatsName1=b;
			}else if(a.equals("log")){
				logName=b;
			}else if(a.equals("ihist")){
				ihistName=b;
			}else if(a.equals("merge") || a.equals("domerge")){
				doMerge_=Tools.parseBoolean(b);
			}else if(a.equals("khist") || a.equals("dokhist")){
				doKhist=Tools.parseBoolean(b);
			}else if(a.equals("filelist")){
				fileListName=b;
			}else if(a.equals("compress")){
				compress=Tools.parseBoolean(b);
			}else if(a.equals("dna")){
				dnaArtifactFlag=Tools.parseBoolean(b);
			}else if(a.equals("rna")){
				rnaArtifactFlag=Tools.parseBoolean(b);
				dnaArtifactFlag=!rnaArtifactFlag; //This line requested by Bryce.
			}else if(a.equals("phix") || a.equals("removephix")){
				phixFlag=Tools.parseBoolean(b);
			}else if(a.equals("lambda") || a.equals("removelambda")){
				lambdaFlag=Tools.parseBoolean(b);
			}else if(a.equals("pjet")){
				pjetFlag=Tools.parseBoolean(b);
			}else if(a.equals("mtst")){
				mtstFlag=Tools.parseBoolean(b);
			}else if(a.equals("kapa")){
				kapaFlag=Tools.parseBoolean(b);
			}else if(a.equals("jointseq")){
				jointSeq=b;
			}else if(a.equals("nextera") || a.equals("nexteralmp")){
				doNextera_=Tools.parseBoolean(b);
			}else if(a.equals("copyundefined") || a.equals("cu")){
				copyUndefined=Tools.parseBoolean(b);
			}else if(a.equals("ktrim")){
				ktrim=b;
			}else if(a.equals("ftl") || a.equals("forcetrimleft")){
				ftl=Integer.parseInt(b);
			}else if(a.equals("ftr") || a.equals("forcetrimright")){
				ftr=Integer.parseInt(b);
			}else if(a.equals("ftr2") || a.equals("forcetrimright2")){
				ftr2=Integer.parseInt(b);
			}else if(a.equals("mink")){
				mink=Integer.parseInt(b);
			}else if(a.equals("k")){
				assert(false) : "To specify kmer length, use filterk, trimk, mapk, or normalizek instead of just 'k'";
				filter_k=Integer.parseInt(b);
			}else if(a.equals("filterk")){
				filter_k=Integer.parseInt(b);
			}else if(a.equals("trimk")){
				trim_k=Integer.parseInt(b);
			}else if(a.equals("mapk")){
				map_k=Integer.parseInt(b);
			}else if(a.equals("normalizek") || a.equals("normk") || a.equals("ecck")){
				normalize_k=Integer.parseInt(b);
			}else if(a.equals("filterhdist")){
				hdist_filter=Integer.parseInt(b);
			}else if(a.equals("spikeink")){
				spikein_k=Integer.parseInt(b);
			}else if(a.equals("spikeinhdist")){
				hdist_spikein=Integer.parseInt(b);
			}else if(a.equals("filterqhdist")){
				qhdist_filter=Integer.parseInt(b);
			}else if(a.equals("trimhdist")){
				hdist_trim=Integer.parseInt(b);
			}else if(a.equals("trimhdist2")){
				hdist2_trim=Integer.parseInt(b);
			}else if(a.equals("ribohdist")){
				hdist_ribo=Integer.parseInt(b);
			}else if(a.equals("riboedist") || a.equals("riboedits")){
				edist_ribo=Integer.parseInt(b);
			}else if(a.equals("maq")){
				if(b.indexOf(',')>-1){
					String[] x=b.split(",");
					assert(x.length==2) : "maq should be length 1 or 2 (at most 1 comma).\nFormat: maq=quality,bases; e.g. maq=10 or maq=10,20";
					minAvgQuality=Byte.parseByte(x[0]);
					minAvgQualityBases=Integer.parseInt(x[1]);
				}else{
					minAvgQuality=Byte.parseByte(b);
				}
			}else if(a.equals("forcetrimmod") || a.equals("forcemrimmodulo") || a.equals("ftm")){
				forceTrimModulo=Integer.parseInt(b);
			}else if(a.equals("trimq")){
				trimq=Byte.parseByte(b);
			}else if(a.equals("qtrim")){
				if(b==null){qtrim="r";}
				else if(b.equalsIgnoreCase("left") || b.equalsIgnoreCase("l")){qtrim="l";qtrimFlag=true;}
				else if(b.equalsIgnoreCase("right") || b.equalsIgnoreCase("r")){qtrim="r";qtrimFlag=true;}
				else if(b.equalsIgnoreCase("both") || b.equalsIgnoreCase("rl") || b.equalsIgnoreCase("lr")){qtrim="lr";qtrimFlag=true;}
				else if(Tools.isDigit(b.charAt(0))){
					trimq=Byte.parseByte(b);
					qtrimFlag=(trimq>=0);
					qtrim=(qtrimFlag ? "lr" : "f");
				}else{
					qtrimFlag=Tools.parseBoolean(b);
					qtrim=""+qtrimFlag;
				}
			}else if(a.equals("optitrim") || a.equals("otf") || a.equals("otm")){
				if(b!=null && (b.charAt(0)=='.' || Tools.isDigit(b.charAt(0)))){
					TrimRead.optimalMode=true;
					TrimRead.optimalBias=Float.parseFloat(b);
					assert(TrimRead.optimalBias>=0 && TrimRead.optimalBias<1);
				}else{
					TrimRead.optimalMode=Tools.parseBoolean(b);
				}
			}else if(a.equals("maxns")){
				maxNs=Integer.parseInt(b);
			}else if(a.equals("usetmpdir")){
				writeTempToTmpdir=Tools.parseBoolean(b);
			}else if(a.equals("tmpdir")){
				tmpDir=b;
				writeTempToTmpdir=(b!=null);
			}else if(a.equals("delete") || a.equals("deletetemp")){
				deleteTemp=Tools.parseBoolean(b);
			}else if(a.equals("humanpath")){
				humanPath=b;
			}else if(a.equals("catpath")){
				catPath=b;
			}else if(a.equals("dogpath")){
				dogPath=b;
			}else if(a.equals("mousepath")){
				dogPath=b;
			}else if(a.equalsIgnoreCase("mouseCatDogHumanPath")){
				mouseCatDogHumanPath=b;
			}else if(a.equals("mapref") || a.equals("maprefs")){
				if(b==null){mappingRefs.clear();}
				else{
					for(String s : b.split(",")){
						mappingRefs.add(s);
					}
				}
			}else if(a.equals("chastityfilter") || a.equals("cf") || a.equals("chastity")){
				chastityfilter=b;
			}else if(a.equals("failnobarcode")){
				failnobarcode=b;
			}else if(a.equals("badbarcodes") || a.equals("barcodefilter")){
				barcodefilter=b;
			}else if(a.equals("barcodes") || a.equals("barcode")){
				barcodes=b;
			}else if(a.equals("extend")){
				extendFlag=Tools.parseBoolean(b);
			}else if(a.equals("taxlist") || a.equals("tax") || a.equals("taxa") || a.equals("taxid")){
				taxList=b;
			}else if(a.equals("taxtree") || a.equals("tree")){
				taxTree=b;
			}else if(a.equals("loadgitable")){
				loadGiTable=Tools.parseBoolean(b);
			}else if(a.equals("gitable")){
				giTable=b;
				loadGiTable=(b!=null);
			}else if(a.equals("taxlevel") || a.equals("level")){
				taxLevel=b;
			}else if(a.equals("microberef")){
				commonMicrobesRef=b;
			}else if(a.equals("microbepath")){
				commonMicrobesPath=b;
			}else if(a.equals("microbebuild")){
				commonMicrobesBuild=Integer.parseInt(b);
			}else if(a.equals("ordered")){
				ordered=Tools.parseBoolean(b);
			}
			
			else if(a.equals("sketch")){
				sketchFlag=Tools.parseBoolean(b);
			}else if(a.equals("silvalocal") || a.equals("localsilva")){
				SILVA_LOCAL=Tools.parseBoolean(b);
			}else if(a.equals("sketchdb") || a.equals("sketchserver")){
				sketchDB=b;
			}else if(a.equals("sketchreads")){
				sketchReads=Integer.parseInt(b);
			}else if(a.equals("sketchsamplerate")){
				sketchSamplerate=b;
				assert(b!=null);
			}else if(a.equals("sketchminprob")){
				sketchMinProb=b;
				assert(b!=null);
			}
			
			else if(a.equals("clump") || a.equals("clumpify")){
				doClump=Tools.parseBoolean(b);
			}else if(a.equals("dedupe")){
				removeDuplicates=Tools.parseBoolean(b);
			}else if(a.equals("alldupes")){
				removeAllDuplicates=Tools.parseBoolean(b);
			}else if(a.equals("opticaldupes")){
				removeOpticalDuplicates=Tools.parseBoolean(b);
			}else if(a.equals("edgedupes")){
				removeEdgeDuplicates=Tools.parseBoolean(b);
			}else if(a.equals("dpasses")){
				duplicatePasses=Integer.parseInt(b);
			}else if(a.equals("dsubs")){
				duplicateSubs=Integer.parseInt(b);
			}else if(a.equals("ddist")){
				duplicateDist=Integer.parseInt(b);
			}else if(a.equals("lowcomplexity")){
				lowComplexity=Tools.parseBoolean(b);
			}else if(a.equals("clumpifygroups") || a.equals("groups")){
				clumpifyGroups=Integer.parseInt(b);
			}else if(a.equals("clumpifytmpdir")){
				clumpifyTmpdir=Tools.parseBoolean(b);
			}
			
			else if(a.equals("dryrun")){
				dryrun=Tools.parseBoolean(b);
			}
			
			else{
				//Uncaptured arguments are passed to BBDuk
				primaryArgList.add(arg);
			}
		}
		
		//Maintain clumpified order in later stages
		if(doClump){ordered=true;}
		
		doNextera=doNextera_;
		
		if(commonMicrobeFlag && detectMicrobeFlag){detectMicrobeFlag=false;}
		
//		assert(false) : rnaArtifactFlag+"\n"+primaryArgList+"\n"+libType+"\n"+outDir;
		
		if(writeTempToTmpdir){
			if(tmpDir==null){tmpDir=Shared.tmpdir();}
			if(tmpDir!=null){
				tmpDir=tmpDir.replace('\\', '/');
				if(tmpDir.length()>0 && !tmpDir.endsWith("/")){tmpDir+="/";}
			}
		}else{tmpDir=null;}
		
		if(hdist2_trim<0){hdist2_trim=hdist_trim;}
		
		//Pass overwrite flag to BBDuk
		primaryArgList.add("ow="+overwrite);
		
		if(outDir!=null){
			outDir=outDir.trim().replace('\\', '/');
			if(outDir.length()>0 && !outDir.endsWith("/")){outDir=outDir+"/";}
		}else{outDir="";}
		
		//TODO: Unify Name or File
		{//Prepend output directory to output files
			if(logName!=null){logName=outDir+logName/*+".tmp"*/;} //Add '.tmp' to log file
			if(reproduceName!=null){reproduceName=outDir+reproduceName;}
			if(fileListName!=null){fileListName=outDir+fileListName;}
			if(ihistName!=null){ihistName=outDir+ihistName;}
			if(khistName!=null){khistName=outDir+khistName;}
			if(peaksName!=null){peaksName=outDir+peaksName;}
			if(riboOutFile!=null){riboOutFile=outDir+riboOutFile;}
			if(chloroOutFile!=null){chloroOutFile=outDir+chloroOutFile;}
			if(humanOutFile!=null){humanOutFile=outDir+humanOutFile;}
			if(spikeinOutFile!=null){spikeinOutFile=outDir+spikeinOutFile;}
			if(synthOutFile1!=null){synthOutFile1=outDir+synthOutFile1;}
			if(synthOutFile2!=null){synthOutFile2=outDir+synthOutFile2;}
			if(microbeOutFile!=null){microbeOutFile=outDir+microbeOutFile;}
			if(microbeStatsFile!=null){microbeStatsFile=outDir+microbeStatsFile;}
			if(microbesUsed!=null){microbesUsed=outDir+microbesUsed;}
			if(chloroStatsFile!=null){chloroStatsFile=outDir+chloroStatsFile;}
			
			if(cardinalityName!=null){cardinalityName=outDir+cardinalityName;}
		}
		
		{//Create unique output file names for first and second trimming passes
			if(rqcStatsName!=null){
				rqcStatsName_kt=outDir+"ktrim_"+rqcStatsName;
				rqcStatsName=outDir+rqcStatsName;
			}
			if(kmerStatsName1!=null){
				kmerStatsName_kt=outDir+"ktrim_"+kmerStatsName1;
				kmerStatsName1=outDir+kmerStatsName1;
			}
			if(kmerStatsName2!=null){
				kmerStatsName2=outDir+kmerStatsName2;
			}
			if(scaffoldStatsNameSpikein!=null){
				scaffoldStatsNameSpikein=outDir+scaffoldStatsNameSpikein;
			}
			if(scaffoldStatsName1!=null){
				scaffoldStatsName_kt=outDir+"ktrim_"+scaffoldStatsName1;
				scaffoldStatsName1=outDir+scaffoldStatsName1;
			}
			if(scaffoldStatsName2!=null){
				scaffoldStatsName2=outDir+scaffoldStatsName2;
			}
			if(refStatsName!=null){
				refStatsName=outDir+refStatsName;
			}
		}
		
		doSpikein=(mtstFlag || kapaFlag || !spikeinRefs.isEmpty());
		
		//Determine execution path
		//This may be obsolete now
		if(libType==FRAG || ((libType==LFPE && lfpeLinker==null) || (libType==CLIP && clipLinker==null) || (libType==CLRS && clrsLinker==null))){
			doTrim=(fragAdapterFlag || rnaAdapterFlag);
			doFilter=true;
		}else if(libType==LFPE){
			doTrim=true;
			doFilter=true;
		}else if(libType==CLIP){
			doTrim=true;
			doFilter=true;
		}else if(libType==CLRS){
			doTrim=true;
			doFilter=true;
		}else{
			throw new RuntimeException("Unknown library type.");
		}
		
		//Use combined index if all organisms are specified (common case)
		if(catFlag && dogFlag && humanFlag){
			if(mouseFlag){
				mouseCatDogHumanFlag=true;
			}else{
				catDogHumanFlag=true;
			}
		}
		
		//Turn off individual flags if a combined flag is used
		if(catDogHumanFlag || mouseCatDogHumanFlag){
			mouseFlag=false;
			catFlag=false;
			dogFlag=false;
			humanFlag=false;
		}
		
		if(dogFlag){mappingRefs.add("path="+dogPath);}
		if(catFlag){mappingRefs.add("path="+catPath);}
		if(mouseFlag){mappingRefs.add("path="+mousePath);}
		if(!doMerge_){ihistName=null;}
		doMerge=(ihistName!=null);
		
		//Set final field 'symbols'
		//This is for those magic letters in the filename
		symbols=(symbols_==null ? abbreviation() : symbols_);
		
		assert(in1!=null) : "No input file specified.";
		
		//Create output filename from input filename if no output filename is specified
		if(out1==null){
			
			File f=new File(in1);
			String name=f.getName();
			rawName=ReadWrite.rawName(name);
			int dot=rawName.lastIndexOf('.');
			if(dot>-1){
				out1=rawName.substring(0, dot)+"."+symbols+rawName.substring(dot)+(compress ? ".gz" : "");
			}else{
				out1=rawName+"."+symbols+".fastq"+(compress ? ".gz" : "");
			}
		}else{
			File f=new File(out1);
			String name=f.getName();
			rawName=ReadWrite.rawName(name);
		}
		
		//Ensure temp files have random names
		tempSalt=KmerNormalize.getSalt(out1, 1);
		clumpPrefix="TEMP_CLUMP_"+tempSalt+"_";
		trimPrefix="TEMP_TRIM_"+tempSalt+"_";
		humanPrefix="TEMP_HUMAN_"+tempSalt+"_";
		spikeinPrefix="TEMP_SPIKEIN_"+tempSalt+"_";
		filterPrefix1="TEMP_FILTER1_"+tempSalt+"_";
		filterPrefix2="TEMP_FILTER2_"+tempSalt+"_";
		taxaPrefix="TEMP_TAXA_"+tempSalt+"_";
		microbePrefix="TEMP_MICROBE_"+tempSalt+"_";
		riboPrefix="TEMP_RIBO_"+tempSalt+"_";
		chloroPrefix="TEMP_CHLORO_"+tempSalt+"_";
		
		if(mappingRefs.size()>0){
			mappingPrefix=new String[mappingRefs.size()];
			for(int i=0; i<mappingRefs.size(); i++){
				mappingPrefix[i]="TEMP_MAP_"+tempSalt+"_"+i+"_";
			}
		}else{
			mappingPrefix=null;
		}
		
		if(reproduceName!=null){
			writeReproduceHeader(reproduceName, args, overwrite);
		}
	}

	
	/*--------------------------------------------------------------*/
	/*----------------     Processing Methods      ----------------*/
	/*--------------------------------------------------------------*/
	
	
	/**
	 * Primary method to fully execute the program.
	 */
	public void process(){
		
		//Create output directory
		if(outDir!=null && outDir.length()>0){
			File f=new File(outDir);
			if(!f.exists()){
				f.mkdirs();
			}
		}
		
		//Create log file
		if(logName!=null){
			boolean b=Tools.canWrite(logName, overwrite);
			assert(b) : "Can't write to "+logName;
			log("start", false);
		}
		
		//Create file list file
		if(fileListName!=null){
			boolean b=Tools.canWrite(fileListName, overwrite);
			assert(b) : "Can't write to "+fileListName;
			
			StringBuilder sb=new StringBuilder();
			if(!doNextera){
				if(out1!=null){sb.append("filtered_fastq="+out1).append('\n');}
				if(out2!=null){sb.append("filtered_fastq_2="+out2).append('\n');}
			}
			
			String x=(outDir==null ? "" : outDir);
			int xlen=x.length();
			
			//Determine whether to append the output directory prefix in each case
			if(ihistName!=null){sb.append("ihist="+(ihistName.startsWith(x) ? ihistName.substring(xlen) : ihistName)).append('\n');}
			if(doKhist){
				if(khistName!=null){sb.append("khist="+(khistName.startsWith(x) ? khistName.substring(xlen) : khistName)).append('\n');}
				if(peaksName!=null){sb.append("peaks="+(peaksName.startsWith(x) ? peaksName.substring(xlen) : peaksName)).append('\n');}
			}
			if(scaffoldStatsNameSpikein!=null && doSpikein){sb.append("spikeinstats="+(scaffoldStatsNameSpikein.startsWith(x) ? scaffoldStatsNameSpikein.substring(xlen) : scaffoldStatsNameSpikein)).append('\n');}
			if(scaffoldStatsName1!=null){sb.append("scafstats1="+(scaffoldStatsName1.startsWith(x) ? scaffoldStatsName1.substring(xlen) : scaffoldStatsName1)).append('\n');}
			if(scaffoldStatsName2!=null){sb.append("scafstats2="+(scaffoldStatsName2.startsWith(x) ? scaffoldStatsName2.substring(xlen) : scaffoldStatsName2)).append('\n');}
			if(refStatsName!=null){sb.append("refstats="+(refStatsName.startsWith(x) ? refStatsName.substring(xlen) : refStatsName)).append('\n');}
			if(riboKmerFlag && riboOutFile!=null){sb.append("ribo="+(riboOutFile.startsWith(x) ? riboOutFile.substring(xlen) : riboOutFile)).append('\n');}
			if((chloroMapFlag || mitoMapFlag || riboMapFlag) && chloroOutFile!=null){sb.append("chloro="+(chloroOutFile.startsWith(x) ? chloroOutFile.substring(xlen) : chloroOutFile)).append('\n');}
			if((chloroMapFlag || mitoMapFlag || riboMapFlag) && chloroStatsFile!=null){sb.append("chloroStats="+(chloroStatsFile.startsWith(x) ? chloroStatsFile.substring(xlen) : chloroStatsFile)).append('\n');}
			if(commonMicrobeFlag && microbeOutFile!=null){
				sb.append("chaffMicrobeReads="+(microbeOutFile.startsWith(x) ? microbeOutFile.substring(xlen) : microbeOutFile)).append('\n');
				sb.append("microbeStats="+(microbeStatsFile.startsWith(x) ? microbeStatsFile.substring(xlen) : microbeStatsFile)).append('\n');
			}else if(detectMicrobeFlag && microbeStatsFile!=null){
				sb.append("microbeStats="+(microbeStatsFile.startsWith(x) ? microbeStatsFile.substring(xlen) : microbeStatsFile)).append('\n');
			}
			if(commonMicrobeFlag && microbesUsed!=null){
				sb.append("microbesUsed="+(microbesUsed.startsWith(x) ? microbesUsed.substring(xlen) : microbesUsed)).append('\n');
			}
			
			if(doFilter && synthOutFile1!=null){sb.append("chaffSynthReads1="+(synthOutFile1.startsWith(x) ? synthOutFile1.substring(xlen) : synthOutFile1)).append('\n');}
			if(doFilter && synthOutFile2!=null){sb.append("chaffSynthReads2="+(synthOutFile2.startsWith(x) ? synthOutFile2.substring(xlen) : synthOutFile2)).append('\n');}
			if((humanFlag || catDogHumanFlag || mouseCatDogHumanFlag) && humanOutFile!=null){sb.append("chaffHumanReads="+(humanOutFile.startsWith(x) ? humanOutFile.substring(xlen) : humanOutFile)).append('\n');}
			
			if(sketchFlag && sketchName!=null){sb.append("sketchResult="+(sketchName.startsWith(x) ? sketchName.substring(xlen) : sketchName)).append('\n');}
			
			if(sb.length()>0){
				ReadWrite.writeString(sb, fileListName, false);
			}
		}
		
		{
			//Sketching has to be done first because after Clumpify, the entire file would need to be processed,
			//rather than just the first X reads
			if(sketchFlag){
				boolean success=runSketch(in1, sketchName, null);
			}
			
			//Calculate number of total steps, to determine when to write to the output directory versus localdisk.
			int step=0;
			final int numSteps=(doClump ? 1 : 0)+(doSpikein ? 1 : 0)+(doFilter ? 2 : 0)+(doTrim ? 1 : 0)+(doNextera ? 1 : 0)+(riboKmerFlag ? 1 : 0)+
					((chloroMapFlag || mitoMapFlag || riboMapFlag) ? 1 : 0)+
					(commonMicrobeFlag ? 1 : 0)+((humanFlag || catDogHumanFlag || mouseCatDogHumanFlag) ? 1 : 0)+mappingRefs.size();
			String inPrefix=null, outPrefix=null;
			
			//Clumpification
			if(doClump){
				step++;
				inPrefix=outPrefix;
				outPrefix=(step<numSteps ? clumpPrefix : null);
//				outstream.println("Clump. step="+step+", in="+in1+", out="+out1+", inPrefix="+inPrefix+", outPrefix="+outPrefix);
				
				final String in1z, in2z, out1z, out2z;
				if(step==1){
					in1z=in1; in2z=in2;
				}else{
					in1z=stripDirs(out1); in2z=stripDirs(out2);
				}
				if(step>=numSteps){
					ReadWrite.ZIPLEVEL=Tools.max(ReadWrite.ZIPLEVEL, 9);
					out1z=out1; out2z=out2;
				}else{
					ReadWrite.ZIPLEVEL=Tools.min(ReadWrite.ZIPLEVEL, 4);
					out1z=stripDirs(out1); out2z=stripDirs(out2);
				}
				
				//in1z=bar.fq
				//out1z=bar.fq
				//inPrefix=TEMP_SKETCH_123455y546
				//outPrefix=TEMP_CLUMP_123455y546
				clumpify(in1z, in2z, out1z, out2z, inPrefix, outPrefix, step);
				
				if(in2!=null && out2==null){
					FASTQ.FORCE_INTERLEAVED=true;
					FASTQ.TEST_INTERLEAVED=false;
				}
				
				if(inPrefix!=null){
					delete(inPrefix, out1z, out2z);//Why is this out, not in?
				}
				
				FASTQ.ASCII_OFFSET=33;
				FASTQ.DETECT_QUALITY=false;
			}
			
			//Adapter trimming
			if(doTrim){
				step++;
				inPrefix=outPrefix;
				outPrefix=(step<numSteps ? trimPrefix : null);
//				outstream.println("Trim. step="+step+", in="+in1+", out="+out1+", inPrefix="+inPrefix+", outPrefix="+outPrefix);
				
				final String in1z, in2z, out1z, out2z;
				if(step==1){
					in1z=in1; in2z=in2;
				}else{
					in1z=stripDirs(out1); in2z=stripDirs(out2);
				}
				if(step>=numSteps){
					ReadWrite.ZIPLEVEL=Tools.max(ReadWrite.ZIPLEVEL, 9);
					out1z=out1; out2z=out2;
				}else{
					ReadWrite.ZIPLEVEL=Tools.min(ReadWrite.ZIPLEVEL, 4);
					out1z=stripDirs(out1); out2z=stripDirs(out2);
				}
				
				ktrim(in1z, in2z, out1z, out2z, inPrefix, outPrefix, step);
				ReadWrite.ZIPLEVEL=Tools.max(ReadWrite.ZIPLEVEL, 6);
				
				//TODO: This junk can go in a function
				if(in2!=null && out2==null){
					FASTQ.FORCE_INTERLEAVED=true;
					FASTQ.TEST_INTERLEAVED=false;
				}
				
				if(inPrefix!=null){
					delete(inPrefix, out1z, out2z);
				}
				
				FASTQ.ASCII_OFFSET=33;
				FASTQ.DETECT_QUALITY=false;
			}
			
			//Spikein filtering
			if(doSpikein){
				step++;
				inPrefix=outPrefix;
				outPrefix=(step<numSteps ? spikeinPrefix : null);
//				outstream.println("Filter. step="+step+", in="+in1+", out="+out1+", inPrefix="+inPrefix+", outPrefix="+outPrefix);
				
				final String in1z, in2z, out1z, out2z;
				if(step==1){
					in1z=in1; in2z=in2;
				}else{
					in1z=stripDirs(out1); in2z=stripDirs(out2);
				}
				if(step>=numSteps){
					ReadWrite.ZIPLEVEL=Tools.max(ReadWrite.ZIPLEVEL, 9);
					out1z=out1; out2z=out2;
				}else{
					out1z=stripDirs(out1); out2z=stripDirs(out2);
				}
				
				filterSpikein(in1z, in2z, out1z, out2z, spikeinOutFile, inPrefix, outPrefix, step);
				
				if(in2!=null && out2==null){
					FASTQ.FORCE_INTERLEAVED=true;
					FASTQ.TEST_INTERLEAVED=false;
				}
				
				if(step>1){
					delete(inPrefix, out1z, out2z);
				}
			}
			
			//Synthetic contaminant filtering
			if(doFilter){
				step++;
				inPrefix=outPrefix;
				outPrefix=(step<numSteps ? filterPrefix1 : null);
//				outstream.println("Filter. step="+step+", in="+in1+", out="+out1+", inPrefix="+inPrefix+", outPrefix="+outPrefix);
				
				final String in1z, in2z, out1z, out2z;
				if(step==1){
					in1z=in1; in2z=in2;
				}else{
					in1z=stripDirs(out1); in2z=stripDirs(out2);
				}
				if(step>=numSteps){
					ReadWrite.ZIPLEVEL=Tools.max(ReadWrite.ZIPLEVEL, 9);
					out1z=out1; out2z=out2;
				}else{
					out1z=stripDirs(out1); out2z=stripDirs(out2);
				}
				
				filter1(in1z, in2z, out1z, out2z, synthOutFile1, inPrefix, outPrefix, step);
				
				if(in2!=null && out2==null){
					FASTQ.FORCE_INTERLEAVED=true;
					FASTQ.TEST_INTERLEAVED=false;
				}
				
				if(step>1){
					delete(inPrefix, out1z, out2z);
				}
			}
			
			//Short synthetic contaminant filtering
			if(doFilter){
				step++;
				inPrefix=outPrefix;
				outPrefix=(step<numSteps ? filterPrefix2 : null);
//				outstream.println("Filter. step="+step+", in="+in1+", out="+out1+", inPrefix="+inPrefix+", outPrefix="+outPrefix);
				
				final String in1z, in2z, out1z, out2z;
				if(step==1){
					in1z=in1; in2z=in2;
				}else{
					in1z=stripDirs(out1); in2z=stripDirs(out2);
				}
				if(step>=numSteps){
					ReadWrite.ZIPLEVEL=Tools.max(ReadWrite.ZIPLEVEL, 9);
					out1z=out1; out2z=out2;
				}else{
					out1z=stripDirs(out1); out2z=stripDirs(out2);
				}
				
				filter2(in1z, in2z, out1z, out2z, synthOutFile2, inPrefix, outPrefix, step);
				
				if(in2!=null && out2==null){
					FASTQ.FORCE_INTERLEAVED=true;
					FASTQ.TEST_INTERLEAVED=false;
				}
				
				if(step>1){
					delete(inPrefix, out1z, out2z);
				}
			}
			
			//Non-specific ribosomal RNA removal via kmers
			if(riboKmerFlag){
				step++;
				inPrefix=outPrefix;
				outPrefix=(step<numSteps ? riboPrefix : null);
//				outstream.println("Filter. step="+step+", in="+in1+", out="+out1+", inPrefix="+inPrefix+", outPrefix="+outPrefix);
				
				final String in1z, in2z, out1z, out2z;
				if(step==1){
					in1z=in1; in2z=in2;
				}else{
					in1z=stripDirs(out1); in2z=stripDirs(out2);
				}
				if(step>=numSteps){
					ReadWrite.ZIPLEVEL=Tools.max(ReadWrite.ZIPLEVEL, 9);
					out1z=out1; out2z=out2;
				}else{
					out1z=stripDirs(out1); out2z=stripDirs(out2);
				}
				
				filterRibo(in1z, in2z, out1z, out2z, riboOutFile, inPrefix, outPrefix, step);
				
				if(in2!=null && out2==null){
					FASTQ.FORCE_INTERLEAVED=true;
					FASTQ.TEST_INTERLEAVED=false;
				}
				
				if(step>1){
					delete(inPrefix, out1z, out2z);
				}
			}
			
			if(detectMicrobeFlag){
//				assert(false) : inPrefix+", "+outPrefix+", "+in1+", "+out1;
				inPrefix=outPrefix;
				final String in1z, in2z;
				if(step==1){
					in1z=in1; in2z=in2;
				}else if(step<numSteps){
					in1z=stripDirs(out1); in2z=stripDirs(out2);
				}else{
					inPrefix=null;
					in1z=outDir+stripDirs(out1);
					in2z=(out2==null ? null : outDir+stripDirs(out2));
				}
				
				String ref=commonMicrobesRef;//taxFilter(commonMicrobesRef, "taxa.fa.gz", false, false, true, overwrite, false);
//				assert(false) : in1z+" , "+inPrefix;
				detectCommonMicrobes(in1z, in2z, microbeStatsFile, inPrefix, ref, aggressiveMicrobeFlag);
			}
			
			//Microbial contaminant removal
			if(commonMicrobeFlag){
				step++;
				inPrefix=outPrefix;
				outPrefix=(step<numSteps ? microbePrefix : null);
//				outstream.println("Filter. step="+step+", in="+in1+", out="+out1+", inPrefix="+inPrefix+", outPrefix="+outPrefix);
				
				final String in1z, in2z, out1z, out2z;
				if(step==1){
					in1z=in1; in2z=in2;
				}else{
					in1z=stripDirs(out1); in2z=stripDirs(out2);
				}
				if(step>=numSteps){
					ReadWrite.ZIPLEVEL=Tools.max(ReadWrite.ZIPLEVEL, 9);
					out1z=out1; out2z=out2;
				}else{
					out1z=stripDirs(out1); out2z=stripDirs(out2);
				}

				String ref=taxFilter(commonMicrobesRef, "taxa.fa.gz", microbesUsed, taxLevel, false, false, true, overwrite, false);
//				outstream.println("in1z="+in1z+"\nout1z="+out1z+"\ninPrefix="+inPrefix+"\noutPrefix="+outPrefix);
				
				{//Microbial removal uses a kmer length of 13 for seeding
					int oldMapK=map_k;
					map_k=13;
					removeCommonMicrobes(in1z, in2z, out1z, out2z, microbeOutFile, microbeStatsFile, inPrefix, outPrefix, ref, step, aggressiveMicrobeFlag);
					map_k=oldMapK;
				}
				
				if(in2!=null && out2==null){
					FASTQ.FORCE_INTERLEAVED=true;
					FASTQ.TEST_INTERLEAVED=false;
				}
				
				if(step>1){
					delete(inPrefix, out1z, out2z);
				}
			}
			
			//Organism-specific chloroplast, mito, and ribo contaminant removal via mapping
			//TODO: Describe ref construction
			if(chloroMapFlag || mitoMapFlag || riboMapFlag){
				step++;
				inPrefix=outPrefix;
				outPrefix=(step<numSteps ? chloroPrefix : null);
//				outstream.println("Filter. step="+step+", in="+in1+", out="+out1+", inPrefix="+inPrefix+", outPrefix="+outPrefix);
				
				final String in1z, in2z, out1z, out2z;
				if(step==1){
					in1z=in1; in2z=in2;
				}else{
					in1z=stripDirs(out1); in2z=stripDirs(out2);
				}
				if(step>=numSteps){
					ReadWrite.ZIPLEVEL=Tools.max(ReadWrite.ZIPLEVEL, 9);
					out1z=out1; out2z=out2;
				}else{
					out1z=stripDirs(out1); out2z=stripDirs(out2);
				}
				
				//Disable chloroMapFlag if the organism is not a plant
				if(chloroMapFlag && taxList!=null){
					boolean foundPlant=false;
					TaxTree tree=TaxTree.loadTaxTree(taxTree, outstream, true);
					TaxNode plant=tree.getNodeByName("Viridiplantae");//Name of plant kingdom
					for(String s : taxList.split(",")){
						TaxNode tn=tree.parseNodeFromHeader(s, true);
						if(tn!=null && (tree.commonAncestor(plant, tn)==plant || tree.commonAncestor(plant, tn)==tn)){
							foundPlant=true;
							break;
						}
					}
					if(!foundPlant){
						outstream.println("Disabled chloroMapFlag because organism is not a plant.");
						chloroMapFlag=false;
					}
				}
				
				//A file containing relevant sequences from all available organisms
				final String ref0;
				if(chloroMapFlag){
					if(mitoMapFlag && riboMapFlag){ref0=chloroMitoRiboRef;}
					else if(mitoMapFlag){ref0=chloroMitoRef;}
					else if(riboMapFlag){ref0=chloroRiboRef;}
					else{ref0=chloroplastRef;}
				}else if(mitoMapFlag){
					if(riboMapFlag){ref0=mitoRiboRef;}
					else{ref0=mitoRef;}
				}else{
					ref0=riboRef;
				}
//				String ref=taxFilter(ref0, "chloroMitoRiboRef.fa.gz", true);
				
				//Filter the file to only include specified organisms in the taxlist.
				String ref;
				{//TODO: Work on this
					ArrayList<String> refList=new ArrayList<String>(3);
					if(chloroMapFlag){refList.add(chloroplastRef);}
					if(mitoMapFlag){refList.add(mitoRef);}
					if(riboMapFlag){refList.add(riboRef);}
					ref=taxFilterList(refList, "chloroMitoRiboRef.fa.gz", true, true);
					if(ref!=null){
						File f=new File(ref);
						if(!f.exists() || f.length()<200){
							outstream.println("Can't find chloroplast/mito/ribo for taxa; using full dataset.");
							ref=ref0;
						}
					}else{ref=ref0;}
				}
				
				{
					decontamByMapping(in1z, in2z, out1z, out2z, chloroOutFile, chloroStatsFile, inPrefix, outPrefix, ref, step);
				}
				
				//Delete the reference if it was just created.
				//TODO: Maybe this should be retained
				if(!ref0.equals(ref)){
					delete(null, ref);
				}
				
				if(in2!=null && out2==null){
					FASTQ.FORCE_INTERLEAVED=true;
					FASTQ.TEST_INTERLEAVED=false;
				}
				
				if(step>1){
					delete(inPrefix, out1z, out2z);
				}
			}
			
			//Human, cat, dog, and mouse removal
			if(humanFlag || catDogHumanFlag || mouseCatDogHumanFlag){
				step++;
				inPrefix=outPrefix;
				outPrefix=(step<numSteps ? humanPrefix : null);
//				outstream.println("Human. step="+step+", in="+in1+", out="+out1+", inPrefix="+inPrefix+", outPrefix="+outPrefix);
				
				final String in1z, in2z, out1z, out2z;
				if(step==1){
					in1z=in1; in2z=in2;
				}else{
					in1z=stripDirs(out1); in2z=stripDirs(out2);
				}
				if(step>=numSteps){
					ReadWrite.ZIPLEVEL=Tools.max(ReadWrite.ZIPLEVEL, 9);
					out1z=out1; out2z=out2;
				}else{
					out1z=stripDirs(out1); out2z=stripDirs(out2);
				}
				
				dehumanize(in1z, in2z, out1z, out2z, humanOutFile, inPrefix, outPrefix, step, catDogHumanFlag, mouseCatDogHumanFlag, aggressiveHumanFlag);
				
				if(in2!=null && out2==null){
					FASTQ.FORCE_INTERLEAVED=true;
					FASTQ.TEST_INTERLEAVED=false;
				}
				
				Data.unloadAll(); //TODO: See if this needs to be added to other mapping steps
				if(step>1){
					delete(inPrefix, out1z, out2z);
				}
			}
			
			//Removal of other assorted reference sequences by mapping
			//Cat, dog, and mouse individually go here
			if(mappingRefs.size()>0){
				for(int i=0; i<mappingRefs.size(); i++){
					step++;
					inPrefix=outPrefix;
					outPrefix=(step<numSteps ? mappingPrefix[i] : null);
					//				outstream.println("Human. step="+step+", in="+in1+", out="+out1+", inPrefix="+inPrefix+", outPrefix="+outPrefix);
					
					final String in1z, in2z, out1z, out2z;
					if(step==1){
						in1z=in1; in2z=in2;
					}else{
						in1z=stripDirs(out1); in2z=stripDirs(out2);
					}
					if(step>=numSteps){
						ReadWrite.ZIPLEVEL=Tools.max(ReadWrite.ZIPLEVEL, 9);
						out1z=out1; out2z=out2;
					}else{
						out1z=stripDirs(out1); out2z=stripDirs(out2);
					}
					
					decontamByMapping(in1z, in2z, out1z, out2z, null, null, inPrefix, outPrefix, mappingRefs.get(i), step);
					
					if(in2!=null && out2==null){
						FASTQ.FORCE_INTERLEAVED=true;
						FASTQ.TEST_INTERLEAVED=false;
					}
					
					Data.unloadAll();
					if(step>1){
						delete(inPrefix, out1z, out2z);
					}
				}
			}
			
			//Nextera LMP library processing
			//TODO: Rename to nexteraLMP
			if(doNextera){
				step++;
				inPrefix=outPrefix;
				outPrefix=null;
//				outstream.println("Nextera. step="+step+", in="+in1+", out="+out1+", inPrefix="+inPrefix+", outPrefix="+outPrefix);
				
				final String in1z, in2z, out1z, out2z;
				if(step==1){
					in1z=in1; in2z=in2;
				}else{
					in1z=stripDirs(out1); in2z=stripDirs(out2);
				}
				
				if(step>=numSteps){
					ReadWrite.ZIPLEVEL=Tools.max(ReadWrite.ZIPLEVEL, 9);
					out1z=out1; out2z=out2;
				}else{
					out1z=stripDirs(out1); out2z=stripDirs(out2);
				}
				
				//Insert size calculation
				//In this case it has to be done before SplitNextera
				if(doMerge){merge(in1z, in2z, inPrefix);}
				
				splitNextera(in1z, in2z, inPrefix, outPrefix, step);
				
				if(in2!=null && out2==null){
					FASTQ.FORCE_INTERLEAVED=true;
					FASTQ.TEST_INTERLEAVED=false;
				}
				
				Data.unloadAll();
				if(step>1){
					delete(inPrefix, out1z, out2z);
				}
			}else{
				if(doMerge){//Insert size calculation
					if(step==0){
						merge(in1, in2, null);
					}else{
						merge(out1, out2, null);
					}
				}
				if(doKhist){//TODO: This does not run in Nextera mode
					if(step==0){
						khist(in1, in2, null);
					}else{
						khist(out1, out2, null);
					}
				}
			}
		}
		
		if(doMerge){
			BBDukF.putRqc("outputReads", BBMerge.readsProcessedTotal*2, true, false);
			BBDukF.putRqc("outputBases", BBMerge.basesProcessedTotal, true, false);
		}
		
		//Write combined stats file (number of reads/bases present/removed in each stage)
		if(rqcStatsName!=null){
			final TextStreamWriter tsw=new TextStreamWriter(rqcStatsName, overwrite, false, false);
			tsw.start();
			tsw.println(BBDukF.rqcString());
			tsw.poisonAndWait();
		}
		
		//Finish writing log file
		if(logName!=null){
			log("RQCFilter complete", true);
			if(logName.endsWith(".tmp")){ //Remove .tmp extension
				String old=logName;
				logName=logName.substring(0, logName.length()-4);
				new File(old).renameTo(new File(logName));
			}
		}
	}
	
	
	/**
	 * Runs Clumpify for compression.
	 * 
	 * @param in1 Primary input reads file (required)
	 * @param in2 Secondary input reads file
	 * @param out1 Primary output reads file (required)
	 * @param out2 Secondary output reads file
	 * @param inPrefix Append this prefix to input filenames
	 * @param outPrefix Append this prefix to output filenames
	 */
	private void clumpify(String in1, String in2, String out1, String out2, String inPrefix, String outPrefix, int stepNum){
		
		log("clumpify start", true);
		
		ArrayList<String> argList=new ArrayList<String>();

		final String inPre=(inPrefix==null ? "" : (tmpDir==null ? outDir : tmpDir)+inPrefix);
		final String outPre=(outPrefix==null ? outDir : (tmpDir==null ? outDir : tmpDir)+outPrefix);
		
		{//Fill list with Clumpify arguments
			if(pigz!=null){argList.add("pigz="+pigz);}
			if(unpigz!=null){argList.add("unpigz="+unpigz);}
			argList.add("zl="+(zl==null ? ""+ReadWrite.ZIPLEVEL : zl));
			argList.add("reorder");
			
			//Set read I/O files
			if(in1!=null){argList.add("in1="+inPre+in1);}
			if(in2!=null){argList.add("in2="+inPre+in2);}
			if(out1!=null){argList.add("out1="+outPre+out1);}
			if(out2!=null){argList.add("out2="+outPre+out2);}

			//Set dedupe params
			if(removeDuplicates){argList.add("dedupe");}
			if(removeAllDuplicates){argList.add("allduplicates");}
			if(removeOpticalDuplicates || removeEdgeDuplicates){argList.add("optical");}
			if(removeEdgeDuplicates){argList.add("spany"); argList.add("adjacent");}
			if(duplicateSubs>=0){argList.add("dsubs="+duplicateSubs);}
			if(duplicateDist>=0){argList.add("ddist="+duplicateDist);}
			if(duplicatePasses>0){argList.add("passes="+duplicatePasses);}
			if(lowComplexity){argList.add("lowcomplexity="+lowComplexity);}
			if(clumpifyGroups>0){argList.add("groups="+clumpifyGroups);}
			if(clumpifyTmpdir){argList.add("usetmpdir="+clumpifyTmpdir);}
		}
		
		String[] args=argList.toArray(new String[0]);
		
		if(reproduceName!=null){
			writeReproduceFile(reproduceName, "clumpify.sh", args);
		}
		
		if(!dryrun){//Run Clumpify
			Clumpify c=new Clumpify(args);
			try {
				c.process(new Timer());
			} catch (Exception e) {
				e.printStackTrace();
				log("failed", true);
				System.exit(1);
			}
		}
		
		//Optionally append files to file list here
		
		log("clumpify finish", true);
	}
	
	
	/**
	 * Runs BBDuk to perform:
	 * Kmer trimming, short read removal.
	 * 
	 * @param in1 Primary input reads file (required)
	 * @param in2 Secondary input reads file
	 * @param out1 Primary output reads file (required)
	 * @param out2 Secondary output reads file
	 * @param inPrefix Append this prefix to input filenames
	 * @param outPrefix Append this prefix to output filenames
	 */
	private void ktrim(String in1, String in2, String out1, String out2, String inPrefix, String outPrefix, int stepNum){
		
		log("ktrim start", true);
		ktrimFlag=true;
		
		ArrayList<String> argList=new ArrayList<String>();

		final String inPre=(inPrefix==null ? "" : (tmpDir==null ? outDir : tmpDir)+inPrefix);
		final String outPre=(outPrefix==null ? outDir : (tmpDir==null ? outDir : tmpDir)+outPrefix);
		
		{//Fill list with BBDuk arguments
			argList.add("ktrim="+(ktrim==null ? "f" : ktrim));
			if(ordered){argList.add("ordered");}
			if(minLen>0){argList.add("minlen="+minLen);}
			if(minLenFraction>0){argList.add("minlenfraction="+minLenFraction);}
			if((libType!=CLIP)){
				argList.add("mink="+mink);
				if(libType==FRAG && ("r".equalsIgnoreCase(ktrim) || "right".equalsIgnoreCase(ktrim))){
					if(tboFlag){argList.add("tbo");}
					if(tpeFlag){argList.add("tpe");}
				}
				argList.add("rcomp=f");
				argList.add("overwrite="+overwrite);
				argList.add("k="+trim_k);
				argList.add("hdist="+hdist_trim);
				if(hdist2_trim>=0){
					argList.add("hdist2="+hdist2_trim);
				}
				if(forceTrimModulo>0){
					argList.add("ftm="+forceTrimModulo);
				}
			}
			if(pigz!=null){argList.add("pigz="+pigz);}
			if(unpigz!=null){argList.add("unpigz="+unpigz);}
			argList.add("zl="+(zl==null ? ""+ReadWrite.ZIPLEVEL : zl));
			
			//Pass along uncaptured arguments
			for(String s : primaryArgList){argList.add(s);}

			//Set read I/O files
			if(in1!=null){argList.add("in1="+inPre+in1);}
			if(in2!=null){argList.add("in2="+inPre+in2);}
			if(out1!=null){argList.add("out1="+outPre+out1);}
			if(out2!=null){argList.add("out2="+outPre+out2);}

//			if(rqcStatsName!=null){al.add("rqc="+rqcStatsName_kt);} //Old style for 2 log files
			if(rqcStatsName!=null){argList.add("rqc=hashmap");}
			if(kmerStatsName_kt!=null){argList.add("outduk="+kmerStatsName_kt);}
			if(scaffoldStatsName_kt!=null){argList.add("stats="+scaffoldStatsName_kt);}
			
			if(copyUndefined){argList.add("cu");}
			
			argList.add("loglog"); //Cardinality
		}
		
		{//Add BBDuk references
			ArrayList<String> refs=new ArrayList<String>();

			if(libType==FRAG){
				if(fragAdapterFlag){refs.add(fragAdapter);}
				if(rnaAdapterFlag){refs.add(rnaAdapter);}
			}else if(libType==LFPE){
				refs.add(lfpeLinker);
			}else if(libType==CLIP){
//				refs.add(clipLinker);
				if(clipLinker!=null){
					argList.add("literal="+clipLinker);
					{//Special processing for literal strings of approx 4bp
						String[] split=clipLinker.split(",");
						int min=split[0].length();
						for(String s : split){min=Tools.min(min, s.length());}
						argList.add("k="+min);
						argList.add("mink=-1");
						argList.add("mm=f");
						argList.add("hdist=0");
						argList.add("edist=0");
						argList.add("ktrimexclusive=t");
					}
				}else{
					throw new RuntimeException("Null clip linker.");
				}
			}else if(libType==CLRS){
				refs.add(clrsLinker);
			}else{
				throw new RuntimeException("Unknown library type.");
			}
			
			StringBuilder refstring=new StringBuilder();
			for(String ref : refs){
				if(ref!=null){
					refstring.append(refstring.length()==0 ? "ref=" : ",");
					refstring.append(ref);
				}
			}
			
			if(refstring!=null && refstring.length()>0){
				argList.add(refstring.toString());
			}
		}
		
		String[] dukargs=argList.toArray(new String[0]);
		
		if(reproduceName!=null){
			writeReproduceFile(reproduceName, "bbduk.sh", dukargs);
		}
		
		if(!dryrun){//Run BBDuk
			BBDukF duk=new BBDukF(dukargs);
			try {
				duk.process();
				outstream.println(format("Adapter Sequence Removed:", duk.readsIn, duk.readsOut, duk.basesIn, duk.basesOut));
				log("#Remaining:\t"+duk.readsOut+" reads\t"+duk.basesOut+" bases", true, false);
			} catch (Exception e) {
				e.printStackTrace();
				log("failed", true);
				System.exit(1);
			}
		}
		
		//Optionally append files to file list here
		
		log("ktrim finish", true);
	}
	
	/**
	 * Runs Seal to perform:
	 * Spike-in removal and quantification.
	 * 
	 * @param in1 Primary input reads file (required)
	 * @param in2 Secondary input reads file
	 * @param out1 Primary output reads file (required)
	 * @param out2 Secondary output reads file
	 * @param inPrefix Append this prefix to input filenames
	 * @param outPrefix Append this prefix to output filenames
	 */
	private void filterSpikein(String in1, String in2, String out1, String out2, String outbad, String inPrefix, String outPrefix,
			int stepNum){
		
		log("filterSpikein start", true);
		
		ArrayList<String> argList=new ArrayList<String>();

		final String inPre=(inPrefix==null ? "" : (tmpDir==null ? outDir : tmpDir)+inPrefix);
		final String outPre=(outPrefix==null ? outDir : (tmpDir==null ? outDir : tmpDir)+outPrefix);
		
//		outstream.println("inPre="+inPre+", outPre="+outPre+", outDir="+outDir+", tmpDir="+tmpDir); //123
		
		{//Fill list with BBDuk arguments
			if(ordered){argList.add("ordered");}
			argList.add("overwrite="+overwrite);
			argList.add("k="+spikein_k);
			argList.add("hdist="+hdist_spikein);
			if(pigz!=null){argList.add("pigz="+pigz);}
			if(unpigz!=null){argList.add("unpigz="+unpigz);}
			argList.add("zl="+(zl==null ? ""+ReadWrite.ZIPLEVEL : zl));
			
//			//Pass along uncaptured arguments
//			for(String s : primaryArgList){argList.add(s);}

			//Set read I/O files
			if(in1!=null){argList.add("in1="+inPre+in1);}
			if(in2!=null){argList.add("in2="+inPre+in2);}
			if(out1!=null){argList.add("outu1="+outPre+out1);}
			if(out2!=null){argList.add("outu2="+outPre+out2);}
			if(outbad!=null){argList.add("outm="+outbad);}

//			if(rqcStatsName!=null){al.add("rqc="+rqcStatsName);} //Old style for 2 log files
//			if(rqcStatsName!=null){argList.add("rqc=hashmap");}
			if(scaffoldStatsNameSpikein!=null){argList.add("stats="+scaffoldStatsNameSpikein);}
			
//			if(copyUndefined){argList.add("cu");}
//
//			argList.add("loglog"); //Cardinality
		}
		
		{//Add references
			if(mtstFlag){spikeinRefs.add(mtstRef);}
			if(kapaFlag){spikeinRefs.add(kapaRef);}
			
			if(spikeinRefs.isEmpty()){assert(false) : "spikeinFlag is true but there are no spikein references.";}

			StringBuilder refstring=new StringBuilder();
			for(String ref : spikeinRefs){
				if(ref!=null){
					refstring.append(refstring.length()==0 ? "ref=" : ",");
					refstring.append(ref);
				}
			}

			if(refstring!=null && refstring.length()>0){
				argList.add(refstring.toString());
			}
		}
		
		String[] sealargs=argList.toArray(new String[0]);
		
		if(reproduceName!=null){
			writeReproduceFile(reproduceName, "seal.sh", sealargs);
		}
		
		if(!dryrun){//Run Seal
			Seal seal=new Seal(sealargs);
			try {
				seal.process();
				outstream.println(format("Spikein Sequence Removed:", seal.readsIn, seal.readsMatched, seal.basesIn, seal.basesMatched));
				log("#Remaining:\t"+seal.readsUnmatched+" reads\t"+seal.basesUnmatched+" bases", true, false);
			} catch (Exception e) {
				e.printStackTrace();
				log("failed", true);
				System.exit(1);
			}
		}
		
		//Optionally append files to file list here
		
		log("filterSpikein finish", true);
	}
	
	/**
	 * Runs BBDuk to perform:
	 * Quality filtering, quality trimming, n removal, short read removal, artifact removal (via kmer filtering), phiX removal, lambda removal.
	 * 
	 * @param in1 Primary input reads file (required)
	 * @param in2 Secondary input reads file
	 * @param out1 Primary output reads file (required)
	 * @param out2 Secondary output reads file
	 * @param inPrefix Append this prefix to input filenames
	 * @param outPrefix Append this prefix to output filenames
	 */
	private void filter1(String in1, String in2, String out1, String out2, String outbad, String inPrefix, String outPrefix,
			int stepNum){
		
		log("filter start", true);
		
		ArrayList<String> argList=new ArrayList<String>();

		final String inPre=(inPrefix==null ? "" : (tmpDir==null ? outDir : tmpDir)+inPrefix);
		final String outPre=(outPrefix==null ? outDir : (tmpDir==null ? outDir : tmpDir)+outPrefix);
		
//		outstream.println("inPre="+inPre+", outPre="+outPre+", outDir="+outDir+", tmpDir="+tmpDir); //123
		
		{//Fill list with BBDuk arguments
			if(minAvgQuality>-1){argList.add("maq="+minAvgQuality+","+minAvgQualityBases);}
			if(qtrim!=null){
				argList.add("trimq="+trimq);
				argList.add("qtrim="+qtrim);
			}
			if(ftl>=0){argList.add("ftl="+ftl);}
			if(ftr>=0){argList.add("ftr="+ftr);}
			if(ftr2>=0){argList.add("ftr2="+ftr2);}
			if(ordered){argList.add("ordered");}
			argList.add("overwrite="+overwrite);
			if(maxNs>=0){argList.add("maxns="+maxNs);}
			if(minLen>0){argList.add("minlen="+minLen);}
			if(minLenFraction>0){argList.add("minlenfraction="+minLenFraction);}
			argList.add("k="+filter_k);
			argList.add("hdist="+hdist_filter);
			if(qhdist_filter>0){argList.add("qhdist="+qhdist_filter);}
			if(pigz!=null){argList.add("pigz="+pigz);}
			if(unpigz!=null){argList.add("unpigz="+unpigz);}
			argList.add("zl="+(zl==null ? ""+ReadWrite.ZIPLEVEL : zl));

			if(chastityfilter!=null){argList.add("cf="+chastityfilter);}
			if(failnobarcode!=null){argList.add("failnobarcode="+failnobarcode);}
			if(barcodefilter!=null){argList.add("barcodefilter="+barcodefilter);}
			if(barcodes!=null){argList.add("barcodes="+barcodes);}
			
			//Pass along uncaptured arguments
			for(String s : primaryArgList){argList.add(s);}

			//Set read I/O files
			if(in1!=null){argList.add("in1="+inPre+in1);}
			if(in2!=null){argList.add("in2="+inPre+in2);}
			if(out1!=null){argList.add("out1="+outPre+out1);}
			if(out2!=null){argList.add("out2="+outPre+out2);}
			if(outbad!=null){argList.add("outm="+outbad);}

//			if(rqcStatsName!=null){al.add("rqc="+rqcStatsName);} //Old style for 2 log files
			if(rqcStatsName!=null){argList.add("rqc=hashmap");}
			if(kmerStatsName1!=null){argList.add("outduk="+kmerStatsName1);}
			if(scaffoldStatsName1!=null){argList.add("stats="+scaffoldStatsName1);}
			
			if(copyUndefined){argList.add("cu");}
			
			if(bisulfite){argList.add("ftr2=1");}
			
			argList.add("loglog"); //Cardinality
		}
		
		{//Add BBDuk references
			bbdukFilterRefs.add(doNextera ? mainArtifactFile_noNextera : mainArtifactFile);
			if(dnaArtifactFlag){
				bbdukFilterRefs.add(doNextera ? artifactFileDna_noNextera : artifactFileDna);
			}
			if(rnaArtifactFlag){
				bbdukFilterRefs.add(artifactFileRna);
			}

			if(phixFlag){bbdukFilterRefs.add(phixRef);}
			if(lambdaFlag){bbdukFilterRefs.add(lambdaRef);}
			if(pjetFlag){bbdukFilterRefs.add(pjetRef);}

			if(libType==FRAG){

			}else if(libType==LFPE){

			}else if(libType==CLIP){

			}else if(libType==CLRS){

			}else{
				throw new RuntimeException("Unknown library type.");
			}

			StringBuilder refstring=new StringBuilder();
			for(String ref : bbdukFilterRefs){
				if(ref!=null){
					refstring.append(refstring.length()==0 ? "ref=" : ",");
					refstring.append(ref);
				}
			}

			if(refstring!=null && refstring.length()>0){
				argList.add(refstring.toString());
			}
		}
		
		String[] dukargs=argList.toArray(new String[0]);
		
		if(reproduceName!=null){
			writeReproduceFile(reproduceName, "bbduk.sh", dukargs);
		}
		
		if(!dryrun){//Run BBDuk
			BBDukF duk=new BBDukF(dukargs);
			try {
				duk.process();
				outstream.println(format("Synthetic Contam Sequence Removed:", duk.readsIn, duk.readsOut, duk.basesIn, duk.basesOut));
				log("#Remaining:\t"+duk.readsOut+" reads\t"+duk.basesOut+" bases", true, false);
			} catch (Exception e) {
				e.printStackTrace();
				log("failed", true);
				System.exit(1);
			}
		}
		
		//Optionally append files to file list here
		
		log("filter finish", true);
	}
	
	/**
	 * Runs BBDuk to perform:
	 * Short contaminant removal.
	 * 
	 * @param in1 Primary input reads file (required)
	 * @param in2 Secondary input reads file
	 * @param out1 Primary output reads file (required)
	 * @param out2 Secondary output reads file
	 * @param inPrefix Append this prefix to input filenames
	 * @param outPrefix Append this prefix to output filenames
	 */
	private void filter2(String in1, String in2, String out1, String out2, String outbad, String inPrefix, String outPrefix,
			int stepNum){
		
		log("short filter start", true);
		
		ArrayList<String> argList=new ArrayList<String>();

		final String inPre=(inPrefix==null ? "" : (tmpDir==null ? outDir : tmpDir)+inPrefix);
		final String outPre=(outPrefix==null ? outDir : (tmpDir==null ? outDir : tmpDir)+outPrefix);
		
//		outstream.println("inPre="+inPre+", outPre="+outPre+", outDir="+outDir+", tmpDir="+tmpDir); //123
		
		{//Fill list with BBDuk arguments
			if(ordered){argList.add("ordered");}
			argList.add("overwrite="+overwrite);
			argList.add("k="+(doNextera ? 19 : 20));
			argList.add("hdist="+hdist_filter);
			if(qhdist_filter>0){argList.add("qhdist="+qhdist_filter);}
			if(pigz!=null){argList.add("pigz="+pigz);}
			if(unpigz!=null){argList.add("unpigz="+unpigz);}
			argList.add("zl="+(zl==null ? ""+ReadWrite.ZIPLEVEL : zl));
			
			//Pass along uncaptured arguments
			for(String s : primaryArgList){argList.add(s);}
			
			//Set read I/O files
			if(in1!=null){argList.add("in1="+inPre+in1);}
			if(in2!=null){argList.add("in2="+inPre+in2);}
			if(out1!=null){argList.add("out1="+outPre+out1);}
			if(out2!=null){argList.add("out2="+outPre+out2);}
			if(outbad!=null){argList.add("outm="+outbad);}
			
//			if(rqcStatsName!=null){argList.add("rqc=hashmap");} //TODO
			if(kmerStatsName2!=null){argList.add("outduk="+kmerStatsName2);}
			if(scaffoldStatsName2!=null){argList.add("stats="+scaffoldStatsName2);}
			
			if(copyUndefined){argList.add("cu");}

			argList.add("loglog"); //Cardinality
		}
		
		{//Add BBDuk references
			argList.add("ref="+(doNextera ? shortArtifactFile_noNextera : shortArtifactFile));
		}
		
		String[] dukargs=argList.toArray(new String[0]);
		
		if(reproduceName!=null){
			writeReproduceFile(reproduceName, "bbduk.sh", dukargs);
		}
		
		if(!dryrun){//Run BBDuk
			BBDukF duk=new BBDukF(dukargs);
			try {
				duk.process();
				outstream.println(format("Short Synthetic Contam Sequence Removed:", duk.readsIn, duk.readsOut, duk.basesIn, duk.basesOut));
				log("#Remaining:\t"+duk.readsOut+" reads\t"+duk.basesOut+" bases", true, false);
			} catch (Exception e) {
				e.printStackTrace();
				log("failed", true);
				System.exit(1);
			}
		}
		
		//Optionally append files to file list here
		
		log("short filter finish", true);
	}
	
	/**
	 * Runs BBDuk to perform:
	 * Ribosomal read removal.
	 * 
	 * @param in1 Primary input reads file (required)
	 * @param in2 Secondary input reads file
	 * @param out1 Primary output reads file (required)
	 * @param out2 Secondary output reads file
	 * @param outRibo Output for ribosomal reads
	 * @param inPrefix Append this prefix to input filenames
	 * @param outPrefix Append this prefix to output filenames
	 */
	private void filterRibo(String in1, String in2, String out1, String out2, String outRibo, String inPrefix, String outPrefix,
			int stepNum){
		
		log("filter ribo start", true);
		
		ArrayList<String> argList=new ArrayList<String>();

		final String inPre=(inPrefix==null ? "" : (tmpDir==null ? outDir : tmpDir)+inPrefix);
		final String outPre=(outPrefix==null ? outDir : (tmpDir==null ? outDir : tmpDir)+outPrefix);
		
//		outstream.println("inPre="+inPre+", outPre="+outPre+", outDir="+outDir+", tmpDir="+tmpDir); //123
		
		{//Fill list with BBDuk arguments
			if(ordered){argList.add("ordered");}
			argList.add("k=31");
			argList.add("ref="+riboKmers);
			if(hdist_ribo>0){argList.add("hdist="+hdist_ribo);}
			if(edist_ribo>0){argList.add("edist="+edist_ribo);}
			
			//Pass along uncaptured arguments
			for(String s : primaryArgList){argList.add(s);}

			//Set read I/O files
			if(in1!=null){argList.add("in1="+inPre+in1);}
			if(in2!=null){argList.add("in2="+inPre+in2);}
			if(out1!=null){argList.add("out1="+outPre+out1);}
			if(out2!=null){argList.add("out2="+outPre+out2);}
			if(outRibo!=null){argList.add("outm="+outRibo);}

//			if(rqcStatsName!=null){al.add("rqc="+rqcStatsName);} //Old style for 2 log files
//			if(rqcStatsName!=null){argList.add("rqc=hashmap");}
//			if(kmerStatsName!=null){argList.add("outduk="+kmerStatsName);}
//			if(scaffoldStatsName!=null){argList.add("stats="+scaffoldStatsName);}
		}
		
		String[] dukargs=argList.toArray(new String[0]);
		
		if(reproduceName!=null){
			writeReproduceFile(reproduceName, "bbduk.sh", dukargs);
		}
		
		if(!dryrun){//Run BBDuk
			BBDukF duk=new BBDukF(dukargs);
			try {
				duk.process();
				outstream.println(format("Ribosomal Sequence Removed:", duk.readsIn, duk.readsOut, duk.basesIn, duk.basesOut));
				log("#Remaining:\t"+duk.readsOut+" reads\t"+duk.basesOut+" bases", true, false);
			} catch (Exception e) {
				e.printStackTrace();
				log("failed", true);
				System.exit(1);
			}
		}
		
		//Optionally append files to file list here
		
		log("filter ribo finish", true);
	}
	
	private static String toPercent(long numerator, long denominator){
		if(denominator<1){return "0.00%";}
		return String.format(Locale.ROOT, "%.2f%%", numerator*100.0/denominator);
	}
	
	private static String format(String prefix, long rin, long rout, long bin, long bout){
		long rrmvd=rin-rout;
		long brmvd=bin-bout;
		return prefix+"\t"+rrmvd+" reads ("+toPercent(rrmvd, rin)+")\t"+brmvd+" bases ("+toPercent(brmvd, bin)+")";
	}
	
	
	/**
	 * Runs SplitNexteraLMP.
	 * 
	 * @param in1 Primary input reads file (required)
	 * @param in2 Secondary input reads file
	 * @param inPrefix Append this prefix to input filenames
	 * @param outPrefix Append this prefix to output filenames
	 */
	private void splitNextera(String in1, String in2, String inPrefix, String outPrefix, int stepNum){
		
		log("splitNextera start", true);
		splitNexteraFlag=true;
		
		ArrayList<String> argList=new ArrayList<String>();

		final String inPre=(inPrefix==null ? "" : (tmpDir==null ? outDir : tmpDir)+inPrefix);
		final String outPre=(outPrefix==null ? outDir : (tmpDir==null ? outDir : tmpDir)+outPrefix);
		
		final String lmpName, fragName, unknownName, singletonName;
		final String statsName=outPre+nexteraStats;
		
		int dot=rawName.lastIndexOf('.');
		if(dot>-1){
			lmpName=outPre+rawName.substring(0, dot)+"."+symbols+".lmp"+rawName.substring(dot)+(compress ? ".gz" : "");
			fragName=outPre+rawName.substring(0, dot)+"."+symbols+".frag"+rawName.substring(dot)+(compress ? ".gz" : "");
			unknownName=outPre+rawName.substring(0, dot)+"."+symbols+".unknown"+rawName.substring(dot)+(compress ? ".gz" : "");
			singletonName=outPre+rawName.substring(0, dot)+"."+symbols+".singleton"+rawName.substring(dot)+(compress ? ".gz" : "");
		}else{
			lmpName=outPre+rawName+"."+symbols+".lmp.fastq"+(compress ? ".gz" : "");
			fragName=outPre+rawName+"."+symbols+".frag.fastq"+(compress ? ".gz" : "");
			unknownName=outPre+rawName+"."+symbols+".unknown.fastq"+(compress ? ".gz" : "");
			singletonName=outPre+rawName+"."+symbols+".singleton.fastq"+(compress ? ".gz" : "");
		}
		
		{//Fill list with Nextera arguments
			argList.add("mask");
			argList.add("ow="+overwrite);
			if(minLen>0){argList.add("minlen="+minLen);}
			if(pigz!=null){argList.add("pigz="+pigz);}
			if(unpigz!=null){argList.add("unpigz="+unpigz);}
			argList.add("zl="+(zl==null ? ""+ReadWrite.ZIPLEVEL : zl));

			//Set read I/O files
			if(in1!=null){argList.add("in1="+inPre+in1);}
			if(in2!=null){argList.add("in2="+inPre+in2);}

			argList.add("out="+lmpName);
			argList.add("outu="+unknownName);
			argList.add("outf="+fragName);
			argList.add("outs="+singletonName);
			argList.add("stats="+statsName);
		}
		
		String[] splitargs=argList.toArray(new String[0]);
		
		if(reproduceName!=null){
			writeReproduceFile(reproduceName, "splitnextera.sh", splitargs);
		}
		
		if(!dryrun){//run SplitNexteraLMP
			SplitNexteraLMP split=new SplitNexteraLMP(splitargs);
			try {
				split.process();
				StringBuilder sb=new StringBuilder();
				sb.append("LMP:\t"+split.readsLmp()+" reads\t"+split.basesLmp()+" bases\n");
				sb.append("Frag:\t"+split.readsFrag()+" reads\t"+split.basesFrag()+" bases\n");
				sb.append("Unknown:\t"+split.readsUnk()+" reads\t"+split.basesUnk()+" bases\n");
				sb.append("Single:\t"+split.readsSingle()+" reads\t"+split.basesSingle()+" bases\n");
			} catch (Exception e) {
				e.printStackTrace();
				log("failed", true);
				System.exit(1);
			}
		}
		
		if(fileListName!=null){
			StringBuilder sb=new StringBuilder();
			sb.append("lmp="+lmpName).append('\n');
			sb.append("frag="+fragName).append('\n');
			sb.append("unknown="+unknownName).append('\n');
			sb.append("singleton="+singletonName).append('\n');
			sb.append("nexterastats="+statsName).append('\n');
			
			if(sb.length()>0){
				ReadWrite.writeString(sb, fileListName, true);
			}
		}
		
		log("splitNextera finish", true);
	}
	
	/**
	 * Runs BBMap to perform:
	 * Human contaminant removal.
	 * 
	 * @param in1 Primary input reads file (required)
	 * @param in2 Secondary input reads file
	 * @param out1 Primary output reads file (required)
	 * @param out2 Secondary output reads file
	 * @param inPrefix Append this prefix to input filenames
	 * @param outPrefix Append this prefix to output filenames
	 */
	private void dehumanize(String in1, String in2, String out1, String out2, String outbad, String inPrefix, String outPrefix,
			int stepNum, boolean catDogHuman, boolean mouseCatDogHuman, boolean aggressive){
		
		log("dehumanize start", true);
		
		ArrayList<String> argList=new ArrayList<String>();

		final String inPre=(inPrefix==null ? "" : (tmpDir==null ? outDir : tmpDir)+inPrefix);
		final String outPre=(outPrefix==null ? outDir : (tmpDir==null ? outDir : tmpDir)+outPrefix);
		
		{
			if(ordered){argList.add("ordered");}
			argList.add("k="+map_k);
			argList.add("idtag=t");
			argList.add("usemodulo");
			argList.add("printunmappedcount");
			argList.add("ow="+overwrite);
			argList.add("qtrim=rl");
			argList.add("trimq=10");
			argList.add("untrim");
			argList.add("kfilter=25");
			argList.add("maxsites=1");
			argList.add("tipsearch="+0);
//			argList.add("minhits="+1);
			
			if(aggressive){
				argList.add("minratio=.75");
				argList.add("maxindel=8");
				argList.add("minhits="+1);
				argList.add("bw=26");
				argList.add("bwr=0.22");
				argList.add("build=2");
			}else{
				argList.add("minratio=.9");
				argList.add("maxindel=3");
				argList.add("minhits="+2);
				argList.add("bw=12");
				argList.add("bwr=0.16");
				argList.add("fast="+true);
				argList.add("maxsites2=10");
			}
			
			if(outbad!=null){argList.add("outm="+outbad);}
			
			if(mouseCatDogHuman){
				argList.add("path="+mouseCatDogHumanPath);
				if(refStatsName!=null){argList.add("refstats="+refStatsName);}
			}else if(catDogHuman){
				argList.add("path="+catDogHumanPath);
				if(refStatsName!=null){argList.add("refstats="+refStatsName);}
			}else{
				if(humanRef==null){
					argList.add("path="+humanPath);
				}else{
					RefToIndex.NODISK=true;
					argList.add("ref="+humanRef);
				}
			}
			
			if(pigz!=null){argList.add("pigz="+pigz);}
			if(unpigz!=null){argList.add("unpigz="+unpigz);}
			argList.add("zl="+(zl==null ? ""+ReadWrite.ZIPLEVEL : zl));
			
//			//Pass along uncaptured arguments
//			for(String s : primaryArgList){argList.add(s);}

			//Set read I/O files
			if(in1!=null){argList.add("in1="+inPre+in1);}
			if(in2!=null){argList.add("in2="+inPre+in2);}
			if(keepHumanReads){
				if(out1!=null){argList.add("out1="+outPre+out1);}
				if(out2!=null){argList.add("out2="+outPre+out2);}
			}else{
				if(out1!=null){argList.add("outu1="+outPre+out1);}
				if(out2!=null){argList.add("outu2="+outPre+out2);}
			}
			
		}
		
		String[] args=argList.toArray(new String[0]);
		
		if(!dryrun){//Run BBMap
			try {
				if(catDogHuman || mouseCatDogHuman){
					BBSplitter.main(args);
				}else{
					BBMap.main(args);
				}

				outstream.println(format("Human Sequence Removed:", BBMap.lastReadsUsed, BBMap.lastBothUnmapped,
						BBMap.lastBasesUsed, BBMap.lastBothUnmappedBases));
				log("#Remaining:\t"+BBMap.lastBothUnmapped+" reads\t"+BBMap.lastBothUnmappedBases+" bases", true, false);
			} catch (Exception e) {
				e.printStackTrace();
				log("failed", true);
				System.exit(1);
			}
		}
		
		//Clear the index
		Data.unloadAll();
		
		//Unset NODISK
		RefToIndex.NODISK=false;
		
		if(reproduceName!=null){
			writeReproduceFile(reproduceName, "bbmap.sh", args);
		}
		
		//Optionally append files to file list here
		
		log("dehumanize finish", true);
	}
	
	/** Fasta only */
	public static boolean printFileTaxonomy(String in, String results, String treeFile, boolean ow, boolean app, boolean bestEffort){
		if(results==null){return false;}
		TextFile tf=new TextFile(in);
		TextStreamWriter tsw=new TextStreamWriter(results, ow, app, false);
		tsw.start();
		TaxTree tree=TaxTree.loadTaxTree(treeFile, outstream, true);
		LinkedHashSet<TaxNode> nodes=new LinkedHashSet<TaxNode>();
		for(String line=tf.nextLine(); line!=null; line=tf.nextLine()){
			if(line.startsWith(">")){
				TaxNode tn=tree.parseNodeFromHeader(line.substring(1), bestEffort);
				nodes.add(tn);
			}
		}
		tf.close();
		for(TaxNode tn : nodes){
			tsw.println(tn.id+"\t"+tn.levelStringExtended(false)+"\t"+tn.name);
		}
		return tsw.poisonAndWait();
	}
	
	/**
	 * Runs FilterByTaxa to remove sequences from a reference.
	 */
	private String taxFilter(String in, String out, String results, String level, boolean include, boolean bestEffort, boolean log, boolean ow, boolean app){
		if(taxList==null){
			printFileTaxonomy(in, results, taxTree, ow, app, true);
			return in;
		}
		if(log){log("taxFilter start", true);}
		
		String temp=(tmpDir==null ? outDir : tmpDir)+taxaPrefix+out;
		ArrayList<String> argList=new ArrayList<String>();
		
		{
			argList.add("names="+taxList);
			argList.add("tree="+taxTree);
			argList.add("level="+level);
			argList.add("in="+in);
			argList.add("out="+temp);
			argList.add("ow="+ow);
			argList.add("append="+app);
			argList.add("include="+include);
			argList.add("besteffort="+bestEffort);
			if(results!=null){argList.add("results="+results);}
		}
		
		if(loadGiTable){
			GiToNcbi.initialize(giTable);
		}
		
		String[] args=argList.toArray(new String[0]);
		FilterByTaxa fbt=new FilterByTaxa(args);
		fbt.process(new Timer());
		GiToNcbi.unload();
		
		if(reproduceName!=null){
			writeReproduceFile(reproduceName, "filterbytaxa.sh", args);
		}
		
		if(log){log("taxFilter finish", true);}
		Shared.setBuffers();
		
//		outstream.println("*Returning "+(fbt.basesOut>0 ? temp : null));
		
		return fbt.basesOut>0 ? temp : null;
	}
	
	/**
	 * Runs FilterByTaxa to remove sequences from a reference.
	 */
	private String taxFilterList(ArrayList<String> in, String out, boolean include, boolean bestEffort){
		String level=(bestEffort && include) ? "species" : taxLevel;
		
		if(in.size()<2){return taxFilter(in.get(0), out, null, level, include, bestEffort, true, overwrite, false);}
		
		log("taxFilterList start", true);
		
		for(int i=0; i<in.size(); i++){
			taxFilter(in.get(i), out, null, level, include, bestEffort, false, (i==0 && overwrite), i>0);
		}
		
		String temp=(tmpDir==null ? outDir : tmpDir)+taxaPrefix+out;
		
		return temp;
	}
	
	private void detectCommonMicrobes(final String in1, final String in2, final String scafstats, final String inPrefix, final String ref, boolean aggressive){
		
		log("detectCommonMicrobes start", true);
		
		final String inPre=(inPrefix==null ? "" : (tmpDir==null ? outDir : tmpDir)+inPrefix);
		
		if(ref==null){
			String skipped="Tax filter removed all ref sequences; skipping microbe detection.";
			outstream.println(skipped);
			log(skipped, true);
			log("detectCommonMicrobes finish", true);
			return;
		}

		ArrayList<String> argList=new ArrayList<String>();
		{
			argList.add("k="+map_k);
			argList.add("idtag=t");
			argList.add("printunmappedcount");
			argList.add("ow="+overwrite);
			argList.add("qtrim=rl");
			argList.add("trimq=10");
			argList.add("untrim");
			argList.add("build="+commonMicrobesBuild);
			argList.add("ef=0.001");
			if(commonMicrobesPath!=null && commonMicrobesRef.equals(ref) && commonMicrobesRef.startsWith(commonMicrobesPath)){
				RefToIndex.NODISK=false;
				argList.add("path="+commonMicrobesPath);
			}else{
				RefToIndex.NODISK=true;
				argList.add("ref="+ref);
			}
			if(pigz!=null){argList.add("pigz="+pigz);}
			if(unpigz!=null){argList.add("unpigz="+unpigz);}
			argList.add("zl="+(zl==null ? ""+ReadWrite.ZIPLEVEL : zl));
			
			if(aggressive){
				argList.add("minid=.85");
				argList.add("maxindel=8");
				argList.add("minhits="+1);
				argList.add("bw=26");
				argList.add("bwr=0.22");
				argList.add("build=2");
				argList.add("tipsearch="+2);
			}else{
				argList.add("minid=.95");
				argList.add("idfilter=.95");
				argList.add("maxindel=3");
				argList.add("minhits="+2);
				argList.add("bw=12");
				argList.add("bwr=0.16");
				argList.add("fast="+true);
				argList.add("maxsites2=10");
				argList.add("tipsearch="+0);
			}

			//Set read I/O files
			if(in1!=null){argList.add("in1="+inPre+in1);}
			if(in2!=null){argList.add("in2="+inPre+in2);}
//			if(outbad!=null){argList.add("outm="+outbad);}
			if(scafstats!=null){argList.add("scafstats="+scafstats);}
		}

		String[] args=argList.toArray(new String[0]);

		if(!dryrun){//Run BBMap
			try {
				BBMap.main(args);
			} catch (Exception e) {
				e.printStackTrace();
				log("failed", true);
				System.exit(1);
			}
		}

		//Clear the index
		Data.unloadAll();

		//Unset NODISK
		RefToIndex.NODISK=false;

		if(reproduceName!=null){
			writeReproduceFile(reproduceName, "bbmap.sh", args);
		}

		if(ref!=null && !ref.equals(commonMicrobesRef)){delete(null, ref);}

		log("detectCommonMicrobes finish", true);
	}
	
	/**
	 * Runs BBMap to perform:
	 * Microbial contaminant removal.
	 */
	private void removeCommonMicrobes(String in1, String in2, String out1, String out2, String outbad, String scafstats, String inPrefix, String outPrefix,
			final String ref, int stepNum, boolean aggressive){
		
		log("removeCommonMicrobes start", true);
		
		final String inPre=(inPrefix==null ? "" : (tmpDir==null ? outDir : tmpDir)+inPrefix);
		final String outPre=(outPrefix==null ? outDir : (tmpDir==null ? outDir : tmpDir)+outPrefix);
		
		if(ref==null){
			String skipped="Tax filter removed all ref sequences; skipping microbe removal.";
			outstream.println(skipped);
			log(skipped, true);

			try {
				if(in1!=null){
					File a=new File(inPre+in1);
					File b=new File(outPre+out1);
					outstream.println("Renaming "+a+" to "+b);
					assert(a.exists()) : a;
					assert(!b.exists() || overwrite) : b;
					a.renameTo(b);
					writeReproduceFile(reproduceName, "mv", new String[] {a.toString(), b.toString()});
				}
				if(in2!=null && out2!=null){
					File a=new File(inPre+in2);
					File b=new File(outPre+out2);
					outstream.println("Renaming "+a+" to "+b);
					assert(a.exists()) : a;
					assert(!b.exists() || overwrite) : b;
					a.renameTo(b);
					writeReproduceFile(reproduceName, "mv", new String[] {a.toString(), b.toString()});
				}
			} catch (Throwable e) {
				outstream.println(e.getMessage());
				e.printStackTrace();
				log("failed", true);
				System.exit(1);
			}
			log("removeCommonMicrobes finish", true);
			return;
		}

		ArrayList<String> argList=new ArrayList<String>();
		{
			if(ordered){argList.add("ordered");}
			argList.add("quickmatch");
			argList.add("k="+map_k);
			argList.add("idtag=t");
			argList.add("printunmappedcount");
			argList.add("ow="+overwrite);
			argList.add("qtrim=rl");
			argList.add("trimq=10");
			argList.add("untrim");
			argList.add("build="+commonMicrobesBuild);
			argList.add("ef=0.001");
			if(commonMicrobesPath!=null && commonMicrobesRef.equals(ref) && commonMicrobesRef.startsWith(commonMicrobesPath)){
				RefToIndex.NODISK=false;
				argList.add("path="+commonMicrobesPath);
			}else{
				RefToIndex.NODISK=true;
				argList.add("ref="+ref);
			}
			if(pigz!=null){argList.add("pigz="+pigz);}
			if(unpigz!=null){argList.add("unpigz="+unpigz);}
			argList.add("zl="+(zl==null ? ""+ReadWrite.ZIPLEVEL : zl));
			
			if(aggressive){
				argList.add("minid=.85");
				argList.add("maxindel=8");
				argList.add("minhits="+1);
				argList.add("bw=26");
				argList.add("bwr=0.22");
				argList.add("build=2");
				argList.add("tipsearch="+2);
			}else{
				argList.add("minid=.95");
				argList.add("idfilter=.95");
				argList.add("maxindel=3");
				argList.add("minhits="+2);
				argList.add("bw=12");
				argList.add("bwr=0.16");
				argList.add("fast="+true);
				argList.add("maxsites2=10");
				argList.add("tipsearch="+0);
			}

			//Set read I/O files
			if(in1!=null){argList.add("in1="+inPre+in1);}
			if(in2!=null){argList.add("in2="+inPre+in2);}
			if(out1!=null){argList.add("outu1="+outPre+out1);}
			if(out2!=null){argList.add("outu2="+outPre+out2);}
			if(outbad!=null){argList.add("outm="+outbad);}
			if(scafstats!=null){argList.add("scafstats="+scafstats);}
			//			assert(false) : scafstats+", "+microbeStatsFile;
		}

		String[] args=argList.toArray(new String[0]);

		if(!dryrun){//Run BBMap
			try {
				BBMap.main(args);
				outstream.println(format("Microbial Sequence Removed:", BBMap.lastReadsUsed, BBMap.lastBothUnmapped,
						BBMap.lastBasesUsed, BBMap.lastBothUnmappedBases));
				log("#Remaining:\t"+BBMap.lastBothUnmapped+" reads\t"+BBMap.lastBothUnmappedBases+" bases", true, false);
			} catch (Exception e) {
				e.printStackTrace();
				log("failed", true);
				System.exit(1);
			}
		}

		//Clear the index
		Data.unloadAll();

		//Unset NODISK
		RefToIndex.NODISK=false;

		if(reproduceName!=null){
			writeReproduceFile(reproduceName, "bbmap.sh", args);
		}

		if(ref!=null && !ref.equals(commonMicrobesRef)){delete(null, ref);}

		log("removeCommonMicrobes finish", true);
	}
	
	/**
	 * Runs BBMap to perform:
	 * Arbitrary contaminant removal.
	 * 
	 * @param in1 Primary input reads file (required)
	 * @param in2 Secondary input reads file
	 * @param out1 Primary output reads file (required)
	 * @param out2 Secondary output reads file
	 * @param inPrefix Append this prefix to input filenames
	 * @param outPrefix Append this prefix to output filenames
	 */
	private void decontamByMapping(String in1, String in2, String out1, String out2, String outbad, String scafstats, String inPrefix, String outPrefix,
			String ref, int stepNum){
		
		log("decontamByMapping_"+ref+" start", true);
		assert(ref!=null) : "Reference was null.";
		
		ArrayList<String> argList=new ArrayList<String>();
		
		final String inPre=(inPrefix==null ? "" : (tmpDir==null ? outDir : tmpDir)+inPrefix);
		final String outPre=(outPrefix==null ? outDir : (tmpDir==null ? outDir : tmpDir)+outPrefix);
		
		{
			if(ordered){argList.add("ordered");}
			argList.add("minratio=.9");
			argList.add("maxindel=3");
			argList.add("fast="+true);
			argList.add("minhits="+2);
			argList.add("tipsearch="+4);
			argList.add("bw=12");
			argList.add("bwr=0.16");
			argList.add("quickmatch");
			argList.add("k="+map_k);
			argList.add("idtag=t");
//			argList.add("usemodulo");
			argList.add("printunmappedcount");
			argList.add("ow="+overwrite);
			argList.add("qtrim=rl");
			argList.add("trimq=10");
			argList.add("untrim");
			if(ref.startsWith("path=")){
				argList.add(ref);
			}else{
				RefToIndex.NODISK=true;
				argList.add("ref="+ref);
			}
			if(pigz!=null){argList.add("pigz="+pigz);}
			if(unpigz!=null){argList.add("unpigz="+unpigz);}
			argList.add("zl="+(zl==null ? ""+ReadWrite.ZIPLEVEL : zl));
			
//			//Pass along uncaptured arguments
//			for(String s : primaryArgList){argList.add(s);}
			
			//Set read I/O files
			if(in1!=null){argList.add("in1="+inPre+in1);}
			if(in2!=null){argList.add("in2="+inPre+in2);}
			if(out1!=null){argList.add("outu1="+outPre+out1);}
			if(out2!=null){argList.add("outu2="+outPre+out2);}
			if(outbad!=null){argList.add("outm="+outbad);}
			if(scafstats!=null){argList.add("scafstats="+scafstats);}
//			assert(false) : scafstats+", "+microbeStatsFile;
		}
		
		String[] args=argList.toArray(new String[0]);
		
		if(!dryrun){//Run BBMap
			try {
				BBMap.main(args);
				outstream.println(format("Other Contam Sequence Removed:", BBMap.lastReadsUsed, BBMap.lastBothUnmapped,
						BBMap.lastBasesUsed, BBMap.lastBothUnmappedBases));
				log("#Remaining:\t"+BBMap.lastBothUnmapped+" reads\t"+BBMap.lastBothUnmappedBases+" bases", true, false);
			} catch (Exception e) {
				e.printStackTrace();
				log("failed", true);
				System.exit(1);
			}
		}
		
		//Clear the index
		Data.unloadAll();
		
		//Unset NODISK
		RefToIndex.NODISK=false;
		
		if(reproduceName!=null){
			writeReproduceFile(reproduceName, "bbmap.sh", args);
		}
		
		//Optionally append files to file list here
		
		log("decontamByMapping_"+ref+" finish", true);
	}
	
	private boolean runSketch(String in1, String outFname, String outPrefix){
		final String outPre=(outPrefix==null ? outDir : outDir+outPrefix);
		final String fname=outPre+outFname;
		
		boolean success=false;
		String[] split=new String[] {"nt"};
		if(sketchDB!=null){split=sketchDB.split(",");}
		for(int i=0; i<split.length; i++){
			success=(runSketch_inner(in1, fname, split[i], i>0))|success;
		}
		return success;
	}
	
	private boolean runSketch_inner(String in, String fname, String db, boolean append){
		SketchObject.reset();
		ArrayList<String> argList=new ArrayList<String>();
		

		String name=in;
		try {
			if(taxList!=null){
				TaxTree tree=TaxTree.loadTaxTree(taxTree, outstream, true);
				TaxNode tn=tree.parseNodeFromHeader(taxList.split(",")[0], true);
				if(tn!=null){name=tn.name;}
			}
		} catch (Exception e1) {}
		
		argList.add("in="+in);
		argList.add("out="+fname);
		argList.add("reads="+sketchReads);
		argList.add("name="+name);
		argList.add("fname="+in1);
		if(sketchMinProb!=null){argList.add("minprob="+sketchMinProb);}
		if(sketchSamplerate!=null){argList.add("samplerate="+sketchSamplerate);}
		if(sketchMerge){argList.add("merge");}
		argList.add("printname0=f");
		argList.add("records=20");
		argList.add("overwrite="+overwrite);
		argList.add("color=false");
		argList.add("depth");
		argList.add("depth2");
		argList.add("unique2");
		argList.add("volume");
		argList.add("sortbyvolume");
		argList.add("contam2=genus");
		argList.add(db);
		if("silva".equalsIgnoreCase(db)){
			if(SILVA_LOCAL){argList.add("local");}
			argList.add("minkeycount=2");
		}
		if(append){argList.add("append");} //True because db name gets written first.
		else{argList.add("ow");}

		String[] argArray=argList.toArray(new String[0]);
		if(reproduceName!=null){
			writeReproduceFile(reproduceName, "sendsketch.sh", argArray);
		}
		
		try {
//			ReadWrite.writeString("DB:\t"+db, fname, append); //Already printed in query header
			
			if(!dryrun){SendSketch.main(argArray);}
			return true;
		} catch (Throwable e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
			return false;
		}
	}
	
	/**
	 * Runs BBMerge to generate an insert size histogram.
	 * 
	 * @param in1 Primary input reads file (required)
	 * @param in2 Secondary input reads file
	 * @param prefix Append this prefix to input filenames
	 */
	private void merge(String in1, String in2, String prefix){
		
		log("merge start", true);
		
		ArrayList<String> argList=new ArrayList<String>();
		
		final String inPre=(prefix==null ? outDir : (tmpDir==null ? outDir : tmpDir)+prefix);
		
		{//Fill list with BBMerge arguments
			if(mergeStrictness!=null){argList.add(mergeStrictness);}
			argList.add("overwrite="+overwrite);
			
			//Set read I/O files
			if(in1!=null){argList.add("in1="+inPre+in1);}
			if(in2!=null){argList.add("in2="+inPre+in2);}
			
			if(ihistName!=null){argList.add("ihist="+ihistName);}
			if(cardinalityName!=null){argList.add("outc="+cardinalityName);}
			if(pigz!=null){argList.add("pigz="+pigz);}
			if(unpigz!=null){argList.add("unpigz="+unpigz);}
			argList.add("zl="+(zl==null ? ""+ReadWrite.ZIPLEVEL : zl));
			if(fragAdapter!=null){argList.add("adapters="+fragAdapter);}
			
			if(extendFlag){
				argList.add("ecct");
//				argList.add("extend2=20");
//				argList.add("iterations=10");
				argList.add("extend2=100");
				argList.add("rem");
				argList.add("k=62");
				argList.add("prefilter");
				argList.add("prealloc");
				System.gc();
			}
		}
		
		String[] mergeargs=argList.toArray(new String[0]);
		
		if(reproduceName!=null){
			writeReproduceFile(reproduceName, "bbmerge.sh", mergeargs);
		}
		
		if(!dryrun){//run BBMerge
			BBMerge merger=new BBMerge(mergeargs);
			try {
				merger.process();
			} catch (Exception e) {
				e.printStackTrace();
				log("failed", true);
				System.exit(1);
			}
		}
		
		//Optionally append files to file list here
		
		log("merge finish", true);
	}
	
	
	/**
	 * Runs BBNorm or KmerCountExact to generate a kmer frequency histogram.
	 * 
	 * @param in1 Primary input reads file (required)
	 * @param in2 Secondary input reads file
	 * @param prefix Append this prefix to input filenames
	 */
	private void khist(String in1, String in2, String prefix){
		
		log("khist start", true);
		
		ArrayList<String> argList=new ArrayList<String>();

		final String inPre=(prefix==null ? outDir : (tmpDir==null ? outDir : tmpDir)+prefix);
		
		final long cardinality=LogLog.lastCardinality;
		final long capacity=kmerCapacity(12, true);
		outstream.println("cardinality="+cardinality+", capacity="+capacity);
		
		if(cardinality<1 || cardinality*1.5>capacity){ //Too many kmers for exact counts; use BBNorm
			{//Fill list with BBNorm arguments
				argList.add("overwrite="+overwrite);

				//Set read I/O files
				if(in1!=null){argList.add("in1="+inPre+in1);}
				if(in2!=null){argList.add("in2="+inPre+in2);}

				if(khistName!=null){argList.add("khist="+khistName);}
				if(peaksName!=null){argList.add("peaks="+peaksName);}
				if(unpigz!=null){argList.add("unpigz="+unpigz);}
				argList.add("keepall");
				argList.add("prefilter");
				argList.add("passes=1");
				argList.add("bits=16");
				argList.add("minprob=0");
				argList.add("minqual=0");
				argList.add("histcolumns=2");
			}

			String[] khistargs=argList.toArray(new String[0]);

			if(reproduceName!=null){
				writeReproduceFile(reproduceName, "khist.sh", khistargs);
			}

			if(!dryrun){//run KmerNormalize
				try {
					KmerNormalize.main(khistargs);
				} catch (Exception e) {
					e.printStackTrace();
					log("failed", true);
					System.exit(1);
				}
			}
		}else{
			{//Fill list with KmerCountExact arguments
				argList.add("overwrite="+overwrite);
				
				//Set read I/O files
				if(in1!=null){argList.add("in1="+inPre+in1);}
				if(in2!=null){argList.add("in2="+inPre+in2);}

				if(khistName!=null){argList.add("khist="+khistName);}
				if(peaksName!=null){argList.add("peaks="+peaksName);}
				if(unpigz!=null){argList.add("unpigz="+unpigz);}
				
				if(cardinality*4>capacity){
					argList.add("prealloc");
				}
			}

			String[] khistargs=argList.toArray(new String[0]);

			if(reproduceName!=null){
				writeReproduceFile(reproduceName, "kmercountexact.sh", khistargs);
			}

			if(!dryrun){//run KmerCountExact
				try {
					KmerCountExact.main(khistargs);
				} catch (Exception e) {
					e.printStackTrace();
					log("failed", true);
					System.exit(1);
				}
			}
		}
		
		//Optionally append files to file list here
		
		log("khist finish", true);
	}
	
	private static long kmerCapacity(int bytesPerKmer, boolean prealloc){
		System.gc();
		long memory=Runtime.getRuntime().maxMemory();
		double xmsRatio=Shared.xmsRatio();
		long usableMemory=(long)Tools.max(((memory-96000000)*(xmsRatio>0.97 ? 0.82 : 0.72)), memory*0.45);
		long tableMemory=(long)(usableMemory*.95);
		long estimatedKmerCapacity=(long)((tableMemory*1.0/bytesPerKmer)*(prealloc ? 0.9 : 0.6));
		return estimatedKmerCapacity;
	}
	
	/*--------------------------------------------------------------*/
	/*----------------        Helper Methods        ----------------*/
	/*--------------------------------------------------------------*/

	/** Note - Seung-jin needs to be notified when new calls are added. */
	private void log(String message, boolean append){log(message, append, true);}
	
	/**
	 * Log a message in the log file
	 * @param message Message to log
	 * @param append True to append, false to overwrite
	 */
	private void log(String message, boolean append, boolean printTime){
		if(logName!=null){
			ReadWrite.writeString(message+(printTime ? ", "+timeString() : "")+"\n", logName, append);
		}
	}
	
	
	/**
	 * Delete all non-null filenames.
	 * @param prefix Append this prefix to filenames before attempting to delete them
	 * @param names Filenames to delete
	 */
	private void delete(String prefix, String...names){
		if(!deleteTemp){return;}
		log("delete temp files start", true);
		if(names!=null){
			final String pre=(prefix==null ? "" : (tmpDir==null ? outDir : tmpDir)+prefix);
			for(String s : names){
				if(s!=null){
					s=pre+s;
					if(verbose){outstream.println("Trying to delete "+s);}
					File f=new File(s);
					if(f.exists() || dryrun){
						if(!dryrun){f.delete();}
						writeReproduceFile(reproduceName, "rm", new String[] {s});
					}
				}
			}
		}
		log("delete temp files finish", true);
	}
	
	/**
	 * @return String of symbols indicating which processes were applied to the input reads
	 */
	private String abbreviation(){
		StringBuilder sb=new StringBuilder();
		
		if(mainArtifactFile!=null || (rnaArtifactFlag && artifactFileRna!=null) || (dnaArtifactFlag && artifactFileDna!=null)){sb.append("a");}
		
		if(maxNs>=0){sb.append("n");}
//		if(qtrim!=null && !qtrim.equalsIgnoreCase("f") && !qtrim.equalsIgnoreCase("false")){sb.append("q");}
		if(minAvgQuality>0){sb.append("q");}
		
		if(rnaArtifactFlag){sb.append("r");}
		if(dnaArtifactFlag){sb.append("d");}
		
		if(libType==CLIP){sb.append("c");}
		else if(libType==LFPE){sb.append("l");}
		else if(libType==CLRS){sb.append("s");}

		if(phixFlag){sb.append("p");}
		if(humanFlag || catDogHumanFlag || mouseCatDogHumanFlag){sb.append("h");}

//		if(ktrimFlag){sb.append("k");}
		
//		if(doTrim){sb.append("k");}
//		if(qtrimFlag){sb.append("t");}
		
		if(doTrim || qtrimFlag){sb.append("t");}
		
		return sb.toString();
	}
	
	/*--------------------------------------------------------------*/
	/*----------------        Static Methods        ----------------*/
	/*--------------------------------------------------------------*/
	
	/**
	 * TODO:  Some machines are set to UTC rather than PST
	 * @return Timestamp in RQC's format
	 */
	public static String timeString(){
		SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
//		sdf.setTimeZone(TimeZone.getTimeZone("PST"));
		sdf.setTimeZone(TimeZone.getDefault());
		return sdf.format(new Date());
	}
	
	public static String stripDirs(String fname){
		return ReadWrite.stripPath(fname);
	}
	
	/**
	 * Set permissions on these files to 777
	 * @param names List of filenames
	 */
	private static void setPermissions(String...names){
		if(names==null){return;}
		for(String name : names){
			if(name!=null && name.trim().length()>0 && new File(name).exists()){
				ReadWrite.setPermissions(name, true, true, true, false);
			}
		}
	}
	
	/**
	 * Writes a single command to the reproduce file
	 * @param fname Filename to write, including path
	 * @param command Command to add to file
	 * @param args Arguments to the command
	 */
	private static void writeReproduceFile(String fname, String command, String[] args){
		StringBuilder sb=new StringBuilder();
		sb.append(command);
		if(args!=null){
			for(String s : args){
				sb.append(' ').append(s);
			}
		}
		sb.append('\n');
		ReadWrite.writeString(sb, fname, true);
	}
	
	/**
	 * Writes the header for the reproduce file
	 * @param fname Filename to write, including path
	 * @param command Command to add to file
	 * @param args Arguments to the command
	 * @param overwrite Permission to overwrite
	 */
	private static void writeReproduceHeader(String fname, String[] args, boolean overwrite){
		StringBuilder sb=new StringBuilder();
		boolean b=Tools.canWrite(fname, overwrite);
		assert(b) : "Can't write to "+fname;
		sb.append("#!/bin/bash\n");
		sb.append("#BBTools version "+Shared.BBMAP_VERSION_STRING+"\n");
		sb.append("#The steps below recapitulate the output of RQCFilter when run like this:\n");
		sb.append("#rqcfilter.sh");
		if(args!=null){
			for(String s : args){
				sb.append(' ').append(s);
			}
		}
		sb.append('\n');
		sb.append('\n');
		ReadWrite.writeString(sb, fname, false);
	}
	
	/**
	 * @param s String representation of library type
	 * @return Numeric code for library type
	 */
	private static int toLibType(String s){
		if(s==null){return FRAG;}
		s=s.trim().toLowerCase();
		if(s.equals("lfpe")){return LFPE;}
		if(s.equals("clip")){return CLIP;}
		if(s.equals("clrs")){return CLRS;}
		if(s.equals("frag") || s.equals("fragment")){return FRAG;}
		throw new RuntimeException("Unknown library type "+s);
	}
	
	/*--------------------------------------------------------------*/
	/*----------------            Fields            ----------------*/
	/*--------------------------------------------------------------*/
	
	/** Screen output */
	private static PrintStream outstream=System.err;

	/** Synthetic contaminant filtering */
	private final boolean doFilter;
	/** Spikein filtering */
	private final boolean doSpikein;
	/** Clumpify */
	private boolean doClump=false;
	/** Adapter-trimming */
	private final boolean doTrim;
	/** Run BBMerge for insert size calculation */
	private final boolean doMerge;
	/** Run KmerNormalize for kmer histogram generation */
	private boolean doKhist=false;
	/** Do NexteraLMP splitting */
	private final boolean doNextera;
	
	/** Symbols to insert in output filename to denote operations performed */
	private final String symbols;
	
	/** Name of raw input file, minus directory and file extension */
	private final String rawName;
	
	/** Type of library; controls processing methods and references to use */
	private int libType=FRAG;
	/** True to filter rna artifacts */
	private boolean rnaArtifactFlag=false;
	/** True to filter dna artifacts */
	private boolean dnaArtifactFlag=true;
	/** True if phix should be filtered out */
	private boolean phixFlag=true;
	/** True if Lambda should be filtered out by kmer-matching */
	private boolean lambdaFlag=true;
	/** True if pjet should be filtered out */
	private boolean pjetFlag=true;

	/** True if mtst should be quantified and filtered out */
	private boolean mtstFlag=false;
	/** True if mtst should be quantified and filtered out */
	private boolean kapaFlag=false;
	
	/** Enables tbo during adapter trimming */
	private boolean tboFlag=true;
	/** Enables tpe during adapter trimming */
	private boolean tpeFlag=true;
	
	/** Unused */
	private String jointSeq=null;
	/** Toss reads shorter than this */
	private int minLen=25;
	/** Toss reads shorter than this fraction of initial length, after trimming */
	private float minLenFraction=0.333f;
	/** Trim bases at this quality or below */
	private byte trimq=10;
	/** Throw away reads below this average quality after trimming.  Default: 5 */
	private byte minAvgQuality=5;
	/** If positive, calculate the average quality from the first X bases. */
	private int minAvgQualityBases=0;
	/** Trim reads to be equal to 0 modulo this value.  Mainly for 151, 251, and 301bp runs. */
	private int forceTrimModulo=5;
	/** Quality-trimming mode */
	private String qtrim="f";//"rl";
	/** Kmer-trimming mode */
	private String ktrim="r";
	/** Kmer length to use for filtering */
	private int filter_k=31;
	/** Kmer length to use for trimming */
	private int trim_k=23;
	/** Kmer length to use for normalization and error-correction */
	private int normalize_k=31;
	/** Kmer length to use for mapping */
	private int map_k=14;
	/** Shortest kmer to use for trimming */
	private int mink=11;
	/** Throw away reads containing more than this many Ns.  Default: 0 (toss reads with any Ns) */
	private int maxNs=0;
	/** Use this Hamming distance when kmer filtering */
	private int hdist_filter=1;
	/** Use this query Hamming distance when kmer filtering */
	private int qhdist_filter=0;
	/** Use this Hamming distance when kmer trimming */
	private int hdist_trim=1;
	/** Use this Hamming distance when kmer trimming with short kmers */
	private int hdist2_trim=-1;
	/** Use this Hamming distance when kmer trimming with short kmers */
	private int hdist_ribo=0;
	/** Use this Hamming distance when kmer trimming with short kmers */
	private int edist_ribo=0;
	
	/** Kmer length to use for filtering spikeins */
	private int spikein_k=31;
	/** Use this Hamming distance when kmer filtering spikeins */
	private int hdist_spikein=0;
	
	//TODO: Document
	private int ftl=-1;
	private int ftr=-1;
	private int ftr2=-1;
	
	
	/** Merge strictness: strict, normal, loose, vloose */
	private String mergeStrictness="loose";
	
	/** Trim Truseq and Nextera adapters from right side of reads */
	private boolean fragAdapterFlag=false;
	/** Trim Truseq-RNA adapters from right side of reads */
	private boolean rnaAdapterFlag=false;
	
	/** Trim 1bp from right side after adapter-trimming/ */
	private boolean bisulfite=false;

	/** Performed quality-trimming on reads */
	private boolean qtrimFlag=false;
	/** Performed kmer-trimming on reads */
	private boolean ktrimFlag=false;
	/** Performed nextera splitting on reads */
	private boolean splitNexteraFlag=false;
	/** Remove reads mapping to human with high identity */
	private boolean humanFlag=false;
	/** Remove reads mapping to dog with high identity */
	private boolean dogFlag=false;
	/** Remove reads mapping to cat with high identity */
	private boolean catFlag=false;
	/** Remove reads mapping to mouse with high identity */
	private boolean mouseFlag=false;
	/** Remove cat, dog, and human reads at the same time with BBSplit. */
	private boolean catDogHumanFlag=false;
	/** Remove mouse, cat, dog, and human reads at the same time with BBSplit. */
	private boolean mouseCatDogHumanFlag=false;
	/** Perform cat, dog, mouse, and human removal aggressively, using unmasked genomes. */
	private boolean aggressiveHumanFlag=false;
	/** Perform microbe removal aggressively, using unmasked genomes. */
	private boolean aggressiveMicrobeFlag=false;
	/** Remove ribosomal reads via kmer-matching */
	private boolean riboKmerFlag=false;
	/** Remove reads from specific ribosomal sequence with BBMap */
	private boolean riboMapFlag=false;
	/** Remove reads from specific chloroplast sequence with BBMap */
	private boolean chloroMapFlag=false;
	/** Remove reads from specific mitochondrial sequence with BBMap */
	private boolean mitoMapFlag=false;
	/** Remove reads from common microbial contaminants with BBMap */
	private boolean commonMicrobeFlag=false;
	/** Detect but do not remove reads from common microbial contaminants with BBMap */
	private boolean detectMicrobeFlag=false;
	/** Extend reads to merge longer inserts */
	private boolean extendFlag=false;
	/** Query the sketch server */
	private boolean sketchFlag=true;
	/** Use local flag for Silva */
	private boolean SILVA_LOCAL=true;
	/** Sketch server to query */
	private String sketchDB="nt,refseq,silva";
	/** Estimate kmer cardinality */
	private boolean doCardinality=true;
	/** Report, but do not remove, cat/dog/mouse/human sequence */
	private boolean keepHumanReads=false;
	
	private boolean verbose=false;
	private boolean overwrite=true;
	private boolean compress=true;

	private boolean copyUndefined=false;
	
	/** Write temp files to $TMPDIR (localdisk) */
	private boolean writeTempToTmpdir=true;
	
	/** Captures the command line "pigz" flag */
	private String pigz="t";
	/** Captures the command line "unpigz" flag */
	private String unpigz="t";
	/** Captures the command line "zl" flag */
	private String zl;
	
	/** Mode for processing chastity flag in Illumina read names */
	private String chastityfilter="t";
	/** Consider the absence of a barcode to mean failure */
	private String failnobarcode=null;
	/** May be set to true, false, or crash to determine how to handle reads with no barcode */
	private String barcodefilter="crash";
	/** An optional list of literal barcodes that are allowed */
	private String barcodes=null;
	
	/** Arguments to pass to BBDuk */
	private ArrayList<String> primaryArgList=new ArrayList<String>();
	/** References to pass to BBDuk for artifact removal */
	private ArrayList<String> bbdukFilterRefs=new ArrayList<String>();
	/** References to pass to Seal for spikein removal */
	private ArrayList<String> spikeinRefs=new ArrayList<String>();
	/** References to pass to BBMap for contaminant removal */
	private ArrayList<String> mappingRefs=new ArrayList<String>();
	
	/** List of taxa to NOT map against */
	private String taxList=null;
	/** Taxonomic level for filtering */
	private String taxLevel="order";
	/** Only needed if there are gi numbers in the references */
	private boolean loadGiTable=false;
	
	/** Number of reads used for SendSketch */
	private int sketchReads=2000000;
	private String sketchMinProb="0.2";
	private String sketchSamplerate="1.0";
	private boolean sketchMerge=true;
	
	/*--------------------------------------------------------------*/
	/*----------     Clumpify Deduplication Parameters    ----------*/
	/*--------------------------------------------------------------*/

	private boolean removeDuplicates=false;
	private boolean removeAllDuplicates=false;
	private boolean removeOpticalDuplicates=false;
	private boolean removeEdgeDuplicates=false;
	private int duplicatePasses=1;
	private int duplicateSubs=-1;
	private int duplicateDist=-1;
	private boolean lowComplexity=false;
	private int clumpifyGroups=-1;
	private boolean clumpifyTmpdir=false;
	
	/*--------------------------------------------------------------*/
	/*----------------        Read Data Files       ----------------*/
	/*--------------------------------------------------------------*/

	private final String tempSalt;
	
	private final String clumpPrefix;
	private final String trimPrefix;
	private final String humanPrefix;
	private final String spikeinPrefix;
	private final String filterPrefix1;
	private final String filterPrefix2;
	private final String taxaPrefix;
	private final String microbePrefix;
	private final String riboPrefix;
	private final String chloroPrefix;
	private final String[] mappingPrefix;
	
	/** Directory in which to write all files */
	private String outDir="";
	
	/** Directory in which to write all temp files */
	private String tmpDir=Shared.tmpdir();
	
	/** Primary input reads file (required) */
	private String in1=null;
	/** Secondary input reads file */
	private String in2=null;
	/** Primary output reads file (required) */
	private String out1=null;
	/** Secondary output reads file */
	private String out2=null;
	
	private boolean deleteTemp=true;
	
	private boolean ordered=false;
	
	private boolean dryrun=false;
	
	/*--------------------------------------------------------------*/
	/*----------------        Separated Reads       ----------------*/
	/*--------------------------------------------------------------*/

	private String riboOutFile="ribo.fq.gz";
	private String chloroOutFile="chloro.fq.gz";
	private String humanOutFile="human.fq.gz";
	private String spikeinOutFile="spikein.fq.gz";
	private String synthOutFile1="synth1.fq.gz";
	private String synthOutFile2="synth2.fq.gz";
	private String microbeOutFile="microbes.fq.gz";
	
	/*--------------------------------------------------------------*/
	/*----------------           Log Files          ----------------*/
	/*--------------------------------------------------------------*/
	
	private String logName="status.log";
	private String reproduceName="reproduce.sh";
	private String fileListName="file-list.txt";
	
	private String rqcStatsName="filterStats.txt";
	private String kmerStatsName1="kmerStats1.txt";
	private String kmerStatsName2="kmerStats2.txt";
	private String scaffoldStatsName1="scaffoldStats1.txt";
	private String scaffoldStatsName2="scaffoldStats2.txt";
	private String scaffoldStatsNameSpikein="scaffoldStatsSpikein.txt";
	private String refStatsName="refStats.txt";
	private String microbeStatsFile="commonMicrobes.txt";
	private String microbesUsed="microbesUsed.txt";
	private String chloroStatsFile="chloroStats.txt";
	private String nexteraStats="nexteraStats.txt";
	private String ihistName="ihist_merge.txt";
	private String khistName="khist.txt";
	private String peaksName="peaks.txt";
	private String sketchName="sketch.txt";
	
	private String cardinalityName="cardinality.txt";
	
	/** ktrim phase rqc stats file */
	private String rqcStatsName_kt;
	/** ktrim phase stats file */
	private String kmerStatsName_kt;
	/** ktrim phase scaffold stats file */
	private String scaffoldStatsName_kt;
	
	/*--------------------------------------------------------------*/
	/*----------------        Reference Files       ----------------*/
	/*--------------------------------------------------------------*/
	
	//TODO: Replace common prefix with a variable
	//TODO: Need to be backed up to tape
	//TODO: Put all this junk into a config file
	
	private String shortArtifactFile = "/global/projectb/sandbox/gaag/bbtools/data/short.fa";
	private String shortArtifactFile_noNextera = "/global/projectb/sandbox/gaag/bbtools/data/short_noNextera.fa";
	
	private String mainArtifactFile_noNextera = "/global/dna/shared/rqc/ref_databases/qaqc/databases/illumina.artifacts/Illumina.artifacts.2013.12.no_DNA_RNA_spikeins_no_Nextera_junction.fa.gz";
	private String mainArtifactFile = "/global/dna/shared/rqc/ref_databases/qaqc/databases/illumina.artifacts/Illumina.artifacts.2013.12.no_DNA_RNA_spikeins.fa";
	private String artifactFileRna = "/global/dna/shared/rqc/ref_databases/qaqc/databases/illumina.artifacts/RNA_spikeins.artifacts.2012.10.NoPolyA.fa";
	private String artifactFileDna = "/global/dna/shared/rqc/ref_databases/qaqc/databases/illumina.artifacts/DNA_spikeins.artifacts.2012.10.fa";
	private String artifactFileDna_noNextera = "/global/dna/shared/rqc/ref_databases/qaqc/databases/illumina.artifacts/DNA_spikeins.artifacts_no_Nextera_junction.2012.10.fa.gz";
	
	private String phixRef = "/global/dna/shared/rqc/ref_databases/qaqc/databases/phix174_ill.ref.fa";
	private String lambdaRef = "/global/dna/shared/rqc/ref_databases/qaqc/databases/lambda.fa.gz";
	private String lfpeLinker = "/global/dna/shared/rqc/ref_databases/qaqc/databases/lfpe.linker.fa";
	private String clrsLinker = "/global/dna/shared/rqc/ref_databases/qaqc/databases/crelox.fa";
	private String clipLinker = clipLinkerDefault; //A literal string; "CATG" is supposed to be the normal linker.
	
	private String pjetRef = "/global/dna/shared/rqc/ref_databases/qaqc/databases/pJET1.2.fasta";
	private String riboKmers = "/global/projectb/sandbox/gaag/bbtools/ribo/merged_ribokmers20.fa.gz";
	private String allArtifactsLatest = "/global/projectb/sandbox/rqc/qcdb/illumina.artifacts/Illumina.artifacts.fa";
	private String fragAdapter = "/global/projectb/sandbox/gaag/bbtools/data/adapters2.fa";
	private String rnaAdapter = "/global/projectb/sandbox/gaag/bbtools/data/truseq_rna.fa.gz";
	private String mtstRef = "/global/projectb/sandbox/gaag/bbtools/data/mtst.fa";
	private String kapaRef = "/global/projectb/sandbox/gaag/bbtools/data/kapatags.L40.fa";
	
	private String humanPath = "/global/projectb/sandbox/gaag/bbtools/hg19/";
	private String catPath = "/global/projectb/sandbox/gaag/bbtools/cat_genome/";
	private String dogPath = "/global/projectb/sandbox/gaag/bbtools/dog_genome/";
	private String mousePath = "/global/projectb/sandbox/gaag/bbtools/mouse_genome/";
	private String humanRef = null;
	
	private String catDogHumanPath = "/global/projectb/sandbox/gaag/bbtools/catdoghuman/";
	private String mouseCatDogHumanPath = "/global/projectb/sandbox/gaag/bbtools/mousecatdoghuman/";
	
	private String chloroplastRef = "/global/projectb/sandbox/gaag/bbtools/refseqMicrobial/plastid.fa.gz";
	private String mitoRef = "/global/projectb/sandbox/gaag/bbtools/refseqMicrobial/mitochondrion.fa.gz";
	private String chloroMitoRef = "/global/projectb/sandbox/gaag/bbtools/refseqMicrobial/chloro_mito.fa.gz";
	private String chloroMitoRiboRef = "/global/projectb/sandbox/gaag/bbtools/refseqMicrobial/chloro_mito_ribo.fa.gz";
	private String riboRef = "/global/projectb/sandbox/gaag/bbtools/silva/latest/both_sorted.fa.gz";
	private String mitoRiboRef = "/global/projectb/sandbox/gaag/bbtools/refseqMicrobial/mito_ribo.fa.gz";
	private String chloroRiboRef = "/global/projectb/sandbox/gaag/bbtools/refseqMicrobial/chloro_ribo.fa.gz";
	
	private String commonMicrobesPath = "/global/projectb/sandbox/gaag/bbtools/commonMicrobes/";
	private String commonMicrobesRef = "/global/projectb/sandbox/gaag/bbtools/commonMicrobes/fusedERPBBmasked.fa.gz";
	private int commonMicrobesBuild = 1;
	private String taxTree=TaxTree.defaultTreeFile();
	private String giTable=TaxTree.defaultTableFile();
	
	/*--------------------------------------------------------------*/
	/*----------------           Constants          ----------------*/
	/*--------------------------------------------------------------*/
	
	/** Library type codes */
	private static final int FRAG=0, LFPE=1, CLIP=2, CLRS=3;
	private static final String clipLinkerDefault = "CATG";
	
}
