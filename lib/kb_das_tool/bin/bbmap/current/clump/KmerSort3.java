package clump;

import java.io.File;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Locale;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.atomic.AtomicInteger;

import bloom.KCountArray;
import fileIO.ByteFile;
import fileIO.FileFormat;
import fileIO.ReadWrite;
import jgi.BBMerge;
import shared.Parser;
import shared.PreParser;
import shared.ReadStats;
import shared.Shared;
import shared.Timer;
import shared.Tools;
import sort.ReadComparatorID;
import sort.ReadComparatorName;
import stream.ConcurrentReadInputStream;
import stream.ConcurrentReadOutputStream;
import stream.FASTQ;
import stream.FastaReadInputStream;
import stream.Read;
import structures.ListNum;

/**
 * @author Brian Bushnell
 * @date June 20, 2014
 *
 */
public class KmerSort3 {
	
	/*--------------------------------------------------------------*/
	/*----------------        Initialization        ----------------*/
	/*--------------------------------------------------------------*/
	
	/**
	 * Code entrance from the command line.
	 * @param args Command line arguments
	 */
	public static void main(String[] args){
		assert(false);
		main(-1, 1, 1, args);
	}
	
	/**
	 * Code entrance from the command line.
	 * @param args Command line arguments
	 */
	public static void main(long fileMem, int outerPassNum, int outerPasses, String[] args){
		final boolean pigz=ReadWrite.USE_PIGZ, unpigz=ReadWrite.USE_UNPIGZ;
		final float ztd=ReadWrite.ZIP_THREAD_MULT;
		final int mzt=ReadWrite.MAX_ZIP_THREADS;
		final int oldzl=ReadWrite.ZIPLEVEL;
		Timer t=new Timer();
		KmerSort3 x=new KmerSort3(fileMem, outerPassNum, outerPasses, args);
		x.process(t);
		ReadWrite.USE_PIGZ=pigz;
		ReadWrite.USE_UNPIGZ=unpigz;
		ReadWrite.ZIP_THREAD_MULT=ztd;
		ReadWrite.MAX_ZIP_THREADS=mzt;
		ReadWrite.ZIPLEVEL=oldzl;
		
		//Close the print stream if it was redirected
		Shared.closeStream(x.outstream);
	}
	
	/**
	 * Constructor.
	 * @param args Command line arguments
	 */
	public KmerSort3(long fileMem_, int outerPassNum_, int outerPasses_, String[] args){
		
		{//Preparse block for help, config files, and outstream
			PreParser pp=new PreParser(args, getClass(), false);
			args=pp.args;
			outstream=pp.outstream;
		}
		
		ReadWrite.USE_PIGZ=ReadWrite.USE_UNPIGZ=true;
		ReadWrite.MAX_ZIP_THREADS=Shared.threads();
		
		outerPassNum=outerPassNum_;
		outerPasses=outerPasses_;
		Parser parser=new Parser();
		
		for(int i=0; i<args.length; i++){
			String arg=args[i];
			String[] split=arg.split("=");
			String a=split[0].toLowerCase();
			String b=split.length>1 ? split[1] : null;

			if(a.equals("verbose")){
				verbose=KmerComparator.verbose=Tools.parseBoolean(b);
			}else if(a.equals("parse_flag_goes_here")){
				//Set a variable here
			}else if(a.equals("k")){
				k=Integer.parseInt(b);
				assert(k>0 && k<32);
			}else if(a.equals("mincount") || a.equals("mincr")){
				minCount=Integer.parseInt(b);
			}else if(a.equals("rename") || a.equals("addname")){
				addName=Tools.parseBoolean(b);
			}else if(a.equals("shortname") || a.equals("shortnames")){
				if(b!=null && b.equals("shrink")){
					shrinkName=true;
				}else{
					shrinkName=false;
					shortName=Tools.parseBoolean(b);
				}
			}else if(a.equals("rcomp") || a.equals("reversecomplement")){
				rcomp=Tools.parseBoolean(b);
			}else if(a.equals("ecco")){
				ecco=Tools.parseBoolean(b);
			}else if(a.equals("condense") || a.equals("consensus") || a.equals("concensus")){//Note the last one is intentionally misspelled
				condense=Tools.parseBoolean(b);
			}else if(a.equals("correct") || a.equals("ecc")){
				correct=Tools.parseBoolean(b);
			}else if(a.equals("passes")){
				passes=Integer.parseInt(b);
			}
			
			else if(a.equals("dedupe")){
				dedupe=Tools.parseBoolean(b);
			}else if(a.equals("markduplicates")){
				dedupe=Clump.markOnly=Tools.parseBoolean(b);
			}else if(a.equals("markall")){
				boolean x=Tools.parseBoolean(b);
				if(x){
					dedupe=Clump.markOnly=Clump.markAll=true;
				}else{
					Clump.markAll=false;
				}
			}
			
			else if(a.equals("prefilter")){
				KmerReduce.prefilter=Tools.parseBoolean(b);
			}else if(a.equals("groups") || a.equals("g") || a.equals("sets") || a.equals("ways")){
				groups=Integer.parseInt(b);
				splitInput=(groups>1);
			}else if(a.equals("seed")){
				KmerComparator.defaultSeed=Long.parseLong(b);
			}else if(a.equals("hashes")){
				KmerComparator.setHashes(Integer.parseInt(b));
			}else if(a.equals("border")){
				KmerComparator.defaultBorder=Integer.parseInt(b);
			}else if(a.equals("minprob")){
				KmerComparator.minProb=Float.parseFloat(b);
				
			}else if(a.equals("unpair")){
				unpair=Tools.parseBoolean(b);
			}else if(a.equals("repair")){
				repair=Tools.parseBoolean(b);
			}else if(a.equals("namesort") || a.equals("sort")){
				namesort=Tools.parseBoolean(b);
			}else if(a.equals("fetchthreads")){
				fetchThreads=Integer.parseInt(b);
				assert(fetchThreads>0) : fetchThreads+"\nFetch threads must be at least 1.";
			}else if(a.equals("reorder") || a.equals("reorderclumps")){
				//reorder=Tools.parseBoolean(b);
			}else if(a.equals("reorderpaired") || a.equals("reorderclumpspaired")){
//				reorderpaired=Tools.parseBoolean(b);
			}
			
			else if(Clump.parseStatic(arg, a, b)){
				//Do nothing
			}else if(parser.parse(arg, a, b)){
				//do nothing
			}
			
			else{
				outstream.println("Unknown parameter "+args[i]);
				assert(false) : "Unknown parameter "+args[i];
				//				throw new RuntimeException("Unknown parameter "+args[i]);
			}
		}
		Clump.renameConsensus=condense;
		if(dedupe){KmerComparator.compareSequence=true;}
		
		{//Process parser fields
			Parser.processQuality();
			
			maxReads=parser.maxReads;
			
			overwrite=ReadStats.overwrite=parser.overwrite;
			append=ReadStats.append=parser.append;

			in1=parser.in1;
			in2=parser.in2;

			out1=parser.out1;
			out2=parser.out2;
			
			extin=parser.extin;
			extout=parser.extout;
		}
		
		assert(FastaReadInputStream.settingsOK());
		
		if(in1!=null && in2==null && in1.indexOf('#')>-1 && !new File(in1).exists()){
			in2=in1.replace("#", "2");
			in1=in1.replace("#", "1");
		}
		if(in2!=null){
			if(FASTQ.FORCE_INTERLEAVED){outstream.println("Reset INTERLEAVED to false because paired input files were specified.");}
			FASTQ.FORCE_INTERLEAVED=FASTQ.TEST_INTERLEAVED=false;
		}
		
		if(in1==null){throw new RuntimeException("Error - at least one input file is required.");}
		if(!ByteFile.FORCE_MODE_BF1 && !ByteFile.FORCE_MODE_BF2 && Shared.threads()>2){
			ByteFile.FORCE_MODE_BF2=true;
		}

		if(out1!=null && out1.equalsIgnoreCase("null")){out1=null;}
		if(out1!=null && out2==null && out1.indexOf('#')>-1){
			out2=out1.replace("#", "2");
			out1=out1.replace("#", "1");
		}
		
		if(!Tools.testOutputFiles(overwrite, append, false, out1)){
			outstream.println((out1==null)+", "+out1);
			throw new RuntimeException("\n\noverwrite="+overwrite+"; Can't write to output files "+out1+"\n");
		}
		
		if(out1==null){ffout1=ffout2=null;}
		else{
			int g=out1.contains("%") ? groups : 1;
			ffout1=new FileFormat[g];
			ffout2=new FileFormat[g];
			if(g==1){
				ffout1[0]=FileFormat.testOutput(out1, FileFormat.FASTQ, extout, true, overwrite, append, false);
				ffout2[0]=FileFormat.testOutput(out2, FileFormat.FASTQ, extout, true, overwrite, append, false);
			}else{
				ReadWrite.ZIPLEVEL=2;
				ReadWrite.setZipThreadMult(Tools.min(0.5f, 2f/(g+1)));
				for(int i=0; i<g; i++){
					ffout1[i]=FileFormat.testOutput(out1.replaceFirst("%", ""+i), FileFormat.FASTQ, extout, (g<10), overwrite, append, false);
					ffout2[i]=(out2==null ? null : FileFormat.testOutput(out2.replaceFirst("%", ""+i), FileFormat.FASTQ, extout, (g<10), overwrite, append, false));
				}
			}
		}
		
		if(groups>1 && in1.contains("%") && (splitInput || !new File(in1).exists())){
			ffin1=new FileFormat[groups];
			ffin2=new FileFormat[groups];
			for(int i=0; i<groups; i++){
				ffin1[i]=FileFormat.testInput(in1.replaceFirst("%", ""+i), FileFormat.FASTQ, extin, true, true);
				ffin2[i]=in2==null ? null : FileFormat.testInput(in2.replaceFirst("%", ""+i), FileFormat.FASTQ, extin, true, true);
			}
		}else{
			assert(!in1.contains("%") && groups==1) : "The % symbol must only be present in the input filename if groups>1.";
			ffin1=new FileFormat[1];
			ffin1[0]=FileFormat.testInput(in1, FileFormat.FASTQ, extin, true, true);
			ffin2=new FileFormat[1];
			ffin2[0]=FileFormat.testInput(in2, FileFormat.FASTQ, extin, true, true);
			groups=1;
		}
		
		long sizeSum=0, expectedMemSum=0;
		for(FileFormat ff : ffin1){
			long x=new File(ff.name()).length();
			sizeSum+=x;
			if(ff.compressed()){x*=40;}else{x*=8;}
			expectedMemSum+=x;
		}
		for(FileFormat ff : ffin2){
			if(ff!=null){
				long x=new File(ff.name()).length();
				sizeSum+=x;
				if(ff.compressed()){x*=40;}else{x*=8;}
				expectedMemSum+=x;
			}
		}

		expectedSizePerGroup=(sizeSum+groups+1)/(Tools.max(groups, 1));
		expectedMemPerGroup=(expectedMemSum+groups+1)/(Tools.max(groups, 1));
		totalMem=Shared.memAvailable(1);
		fileSize=sizeSum;
		fileMem=fileMem_<1 ? 40*fileSize : fileMem_;
		memRatio=fileMem*1.0/Tools.max(1, fileSize);
		
//		if(groups>1){ReadWrite.USE_UNPIGZ=false;} //Not needed since they are not concurrent
	}
	
	
	/*--------------------------------------------------------------*/
	/*----------------         Outer Methods        ----------------*/
	/*--------------------------------------------------------------*/

	/** Count kmers */
	void preprocess(){
		if(minCount>1){
			if(groups>1){
				table=ClumpTools.table();
				assert(table!=null);
			}else{
				Timer ctimer=new Timer();
				if(verbose){ctimer.start("Counting pivots.");}
				table=ClumpTools.getTable(in1, in2, k, minCount);
				if(verbose){ctimer.stop("Count time: ");}
			}
		}
	}

	/** Create read streams and process all data */
	void process(Timer t){
		
		preprocess();

		final ConcurrentReadOutputStream[] rosa=(ffout1==null ? null : new ConcurrentReadOutputStream[ffout1.length]);
		for(int i=0; rosa!=null && i<rosa.length; i++){
			final int buff=1;

			assert(!out1.equalsIgnoreCase(in1) && !out1.equalsIgnoreCase(in1)) : "Input file and output file have same name.";
			
			rosa[i]=ConcurrentReadOutputStream.getStream(ffout1[i], ffout2[i], null, null, buff, null, false);
			rosa[i].start();
		}
		
		readsProcessed=basesProcessed=diskProcessed=memProcessed=0;
		
		//Process the read stream
		processInner(rosa);
		lastMemProcessed=memThisPass;
		
		table=null;
		ClumpTools.clearTable();
		
		errorState|=ReadStats.writeAll();
		
		t.stop();
		
		double rpnano=readsProcessed/(double)(t.elapsed);
		double bpnano=basesProcessed/(double)(t.elapsed);

		String rpstring=(readsProcessed<100000 ? ""+readsProcessed : readsProcessed<100000000 ? (readsProcessed/1000)+"k" : (readsProcessed/1000000)+"m");
		String rpstring2=readsProcessed+"";
		String bpstring=(basesProcessed<100000 ? ""+basesProcessed : basesProcessed<100000000 ? (basesProcessed/1000)+"k" : (basesProcessed/1000000)+"m");
		
		String cpstring=""+(groups==1 ? clumpsProcessedThisPass : clumpsProcessedTotal);
		String epstring=""+correctionsTotal;
		String dpstring=""+duplicatesTotal;

		String rostring=""+readsOut;
		String bostring=""+basesOut;
		
		while(rpstring.length()<10){rpstring=" "+rpstring;}
		while(bpstring.length()<10){bpstring=" "+bpstring;}

		while(rpstring2.length()<12){rpstring2=" "+rpstring2;}
		while(cpstring.length()<12){cpstring=" "+cpstring;}
		while(epstring.length()<12){epstring=" "+epstring;}
		while(dpstring.length()<12){dpstring=" "+dpstring;}

		while(rostring.length()<12){rostring=" "+rostring;}
		while(bostring.length()<12){bostring=" "+bostring;}
		
		outstream.println("Time:                         \t"+t);
		outstream.println("Reads Processed:    "+rpstring+" \t"+String.format(Locale.ROOT, "%.2fk reads/sec", rpnano*1000000));
		outstream.println("Bases Processed:    "+bpstring+" \t"+String.format(Locale.ROOT, "%.2fm bases/sec", bpnano*1000));
		outstream.println();

		outstream.println("Reads In:         "+rpstring2);
		outstream.println("Clumps Formed:    "+cpstring);
		if(correct){
			outstream.println("Errors Corrected: "+epstring);
		}
		if(dedupe){
			outstream.println("Duplicates Found: "+dpstring);
			outstream.println("Reads Out:        "+rostring);
			outstream.println("Bases Out:        "+bostring);
		}
		if(outerPassNum<outerPasses){outstream.println();}
		
		if(errorState){
			Clumpify.sharedErrorState=true;
			throw new RuntimeException(getClass().getName()+" terminated in an error state; the output may be corrupt.");
		}
	}
	
	/** Collect and sort the reads */
	void processInner(final ConcurrentReadOutputStream rosa[]){
		if(verbose){outstream.println("Making comparator.");}
		KmerComparator kc=new KmerComparator(k, addName, (rcomp || condense || correct));
		
		ClumpList.UNRCOMP=(!rcomp && !condense);
		Timer t=new Timer();
		
		fetchThreads=Tools.min(groups, fetchThreads, ffin1.length);
		assert(fetchThreads>0);
		SynchronousQueue<ArrayList<Read>> listQ=new SynchronousQueue<ArrayList<Read>>();
		ArrayList<FetchThread> alft=fetchReads(kc, fetchThreads, listQ, rosa);
		int poisonCount=0;
		
		for(int group=0; group<groups; group++){
			
			if(verbose){t.start("Fetching reads.");}
			ArrayList<Read> reads=null;
			
			//TODO: There appears to be something unsafe here which could lead to this loop being skipped.
			while(poisonCount<fetchThreads && reads==null){
				try {
					reads=listQ.take();
				} catch (InterruptedException e) {
					// TODO Auto-generated catch block
					e.printStackTrace();
				}
				if(reads==POISON){
					assert(reads.isEmpty());
					poisonCount++;
					if(verbose){System.err.println("Encountered poison; count="+poisonCount);}
					reads=null;
				}
			}
			if(verbose){t.stop("Fetched "+(reads==null ? 0 : reads.size())+" reads: ");}
			
			//Done by FetchThread
//			if(verbose){t.start("Sorting.");}
//			Shared.sort(reads, kc);
//			if(verbose){t.stop("Sort time: ");}
			
			if(verbose){t.start("Making clumps.");}
			readsProcessedThisPass=reads.size();
			ClumpList cl=new ClumpList(reads, k, false);
			clumpsProcessedThisPass=cl.size();
			clumpsProcessedTotal+=clumpsProcessedThisPass;
			if(verbose){t.stop("Clump time: ");}
			
			if(dedupe){
				reads.clear();
				if(verbose){t.start("Deduping.");}
				reads=processClumps(cl, ClumpList.DEDUPE);
				if(verbose){t.stop("Dedupe time: ");}
			}else if(condense){
				reads.clear();
				if(verbose){t.start("Condensing.");}
				reads=processClumps(cl, ClumpList.CONDENSE);
				if(verbose){t.stop("Condense time: ");}
			}else if(correct){
				reads.clear();
				if(verbose){t.start("Correcting.");}
				reads=processClumps(cl, ClumpList.CORRECT);
				if(verbose){t.stop("Correct time: ");}
				
				if(verbose){outstream.println("Seed: "+kc.seed);}
				if(groups>1){
					if(verbose){outstream.println("Reads:        \t"+readsProcessedThisPass);}
					outstream.println("Clumps:       \t"+clumpsProcessedThisPass);
					if(correct){
						outstream.println("Corrections:  \t"+correctionsThisPass);
					}
					outstream.println();
				}
				
				if(passes>1 && groups==1){
					if(verbose){outstream.println("Pass 1.");}
					if(verbose){outstream.println("Reads:        \t"+readsProcessedThisPass);}
					outstream.println("Clumps:       \t"+clumpsProcessedThisPass);
					if(correct){
						outstream.println("Corrections:  \t"+correctionsThisPass);
					}
					outstream.println();
					
					for(int pass=1; pass<passes; pass++){
						kc=new KmerComparator(k, kc.seed<0 ? -1 : kc.seed+1, kc.border-1, kc.hashes, false, kc.rcompReads);
						reads=runOnePass(reads, kc);

						if(verbose){outstream.println("Seed: "+kc.seed);}
						if(verbose){outstream.println("Pass "+(pass+1)+".");}
						if(verbose){outstream.println("Reads:        \t"+readsProcessedThisPass);}
						outstream.println("Clumps:       \t"+clumpsProcessedThisPass);
						if(correct){
							outstream.println("Corrections:  \t"+correctionsThisPass);
						}
						outstream.println();
					}
				}
			}
			
			if(repair || namesort){
				if(groups>1){
					if(verbose){t.start("Name-sorting.");}
					reads=nameSort(reads, false);
					if(verbose){t.stop("Sort time: ");}
				}else{
					if(namesort){
						if(verbose){t.start("Name-sorting.");}
						reads=idSort(reads, repair);
						if(verbose){t.stop("Sort time: ");}
					}else{
						reads=read1Only(reads);
					}
				}
			}
			
			for(Read r : reads){
				readsOut+=r.pairCount();
				basesOut+=r.pairLength();
			}
			
			if(doHashAndSplit || groups==0){
				addToRos(rosa, reads, t, kc);
			}else{
				if(group>0){
					ConcurrentReadOutputStream ros=rosa[group-1];
					errorState|=ReadWrite.closeStream(ros);
				}
				rosa[group].add(reads, 0);
			}
			reads=null;
		}
		
		if(verbose){outstream.println("Closing fetch threads.");}
		while(poisonCount<fetchThreads){
			ArrayList<Read> reads=null;
			try {
				reads=listQ.take();
			} catch (InterruptedException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}
			if(reads==POISON){
				poisonCount++;
				reads=null;
			}
		}
		long readsThisPass=closeFetchThreads(alft);
		if(verbose){outstream.println("Closed fetch threads.");}
		
		if(rosa!=null){
			if(verbose){outstream.println("Waiting for writing to complete.");}
			for(ConcurrentReadOutputStream ros : rosa){
				errorState=ReadWrite.closeStream(ros)|errorState;
			}
			if(verbose){t.stop("Write time: ");}
		}
		
		if(verbose && outerPassNum==outerPasses){outstream.println("Done!");}
	}
	
	private void addToRos(ConcurrentReadOutputStream[] rosa, ArrayList<Read> list, Timer t, KmerComparator old){
		if(rosa==null){return;}
		assert(rosa.length>0);
		if(rosa.length==1){
			if(verbose){t.start("Writing.");}
			rosa[0].add(list, 0);
			return;
		}
		KmerComparator kc=new KmerComparator(old.k, old.seed+1, old.border-1, old.hashes, false, false);
		final int div=rosa.length;
		assert(div==groups);
		@SuppressWarnings("unchecked")
		ArrayList<Read>[] array=new ArrayList[div];
		for(int i=0; i<array.length; i++){
			array[i]=new ArrayList<Read>();
		}
		if(verbose){t.start("Splitting.");}
		hashAndSplit(list, kc, array);
		if(verbose){t.stop("Split time: ");}
		if(verbose){t.start("Writing.");}
		for(int i=0; i<div; i++){
			rosa[i].add(array[i], 0);
			array[i]=null;
		}
		if(verbose){System.err.println("Sent writable reads.");}
	}
	
	private ArrayList<Read> runOnePass(ArrayList<Read> reads, KmerComparator kc){
//		for(Read r : reads){r.obj=null;}//No longer necessary
		
		Timer t=new Timer();
		
		table=null;
		if(minCount>1){
			if(verbose){t.start("Counting pivots.");}
			table=ClumpTools.getTable(reads, k, minCount);
			if(verbose){t.stop("Count time: ");}
		}
		
		if(verbose){t.start("Hashing.");}
		kc.hashThreaded(reads, table, minCount);
		if(verbose){t.stop("Hash time: ");}
		
		if(verbose){t.start("Sorting.");}
		Shared.sort(reads, kc);
		if(verbose){t.stop("Sort time: ");}
		
		if(verbose){t.start("Making clumps.");}
		readsProcessedThisPass=reads.size();
		ClumpList cl=new ClumpList(reads, k, false);
		reads.clear();
		clumpsProcessedThisPass=cl.size();
		clumpsProcessedTotal+=clumpsProcessedThisPass;
		if(verbose){t.stop("Clump time: ");}
		
		if(verbose){t.start("Correcting.");}
		reads=processClumps(cl, ClumpList.CORRECT);
		if(verbose){t.stop("Correct time: ");}
		
		return reads;
	}
	
	public void hashAndSplit(ArrayList<Read> list, KmerComparator kc, ArrayList<Read>[] array){
		int threads=Shared.threads();
		ArrayList<HashSplitThread> alt=new ArrayList<HashSplitThread>(threads);
		for(int i=0; i<threads; i++){alt.add(new HashSplitThread(i, threads, list, kc));}
		for(HashSplitThread ht : alt){ht.start();}
		
		/* Wait for threads to die */
		for(HashSplitThread ht : alt){
			
			/* Wait for a thread to die */
			while(ht.getState()!=Thread.State.TERMINATED){
				try {
					ht.join();
				} catch (InterruptedException e) {
					e.printStackTrace();
				}
			}
			for(int i=0; i<groups; i++){
				array[i].addAll(ht.array[i]);
				ht.array[i]=null;
			}
		}
	}
	
	private class HashSplitThread extends Thread{
		
		@SuppressWarnings("unchecked")
		HashSplitThread(int id_, int threads_, ArrayList<Read> list_, KmerComparator kc_){
			id=id_;
			threads=threads_;
			list=list_;
			kc=kc_;
			array=new ArrayList[groups];
			for(int i=0; i<groups; i++){
				array[i]=new ArrayList<Read>();
			}
		}
		
		@Override
		public void run(){
			for(int i=id; i<list.size(); i+=threads){
				Read r=list.get(i);
				kc.hash(r, null, 0, true);
				ReadKey key=(ReadKey)r.obj;
				array[(int)(kc.hash(key.kmer)%groups)].add(r);
			}
		}
		
		final int id;
		final int threads;
		final ArrayList<Read> list;
		final KmerComparator kc;
		final ArrayList<Read>[] array;
	}
	
	private static ArrayList<Read> nameSort(ArrayList<Read> list, boolean pair){
		Shared.sort(list, ReadComparatorName.comparator);
		if(!pair){return list;}
		
		ArrayList<Read> list2=new ArrayList<Read>(1+list.size()/2);
		
		Read prev=null;
		for(Read r : list){
			if(prev==null){
				prev=r;
				assert(prev.mate==null);
			}else{
				if(prev.id.equals(r.id) || FASTQ.testPairNames(prev.id, r.id, true)){
					prev.mate=r;
					r.mate=prev;
					prev.setPairnum(0);
					r.setPairnum(1);
					list2.add(prev);
					prev=null;
				}else{
					list2.add(prev);
					prev=r;
				}
			}
		}
		return list2;
	}
	
	private static ArrayList<Read> idSort(ArrayList<Read> list, boolean pair){
		Shared.sort(list, ReadComparatorID.comparator);
		if(!pair){return list;}
		
		ArrayList<Read> list2=new ArrayList<Read>(1+list.size()/2);
		
		Read prev=null;
		for(Read r : list){
			if(prev==null){
				prev=r;
				assert(prev.mate==null);
			}else{
				if(prev.numericID==r.numericID){
					assert(prev.pairnum()==0 && r.pairnum()==1) : prev.id+"\n"+r.id;
					prev.mate=r;
					r.mate=prev;
					prev.setPairnum(0);
					r.setPairnum(1);
					list2.add(prev);
					prev=null;
				}else{
					list2.add(prev);
					prev=r;
				}
			}
		}
		return list2;
	}
	
	private static ArrayList<Read> read1Only(ArrayList<Read> list){
		ArrayList<Read> list2=new ArrayList<Read>(1+list.size()/2);
		for(Read r : list){
			assert(r.mate!=null);
			if(r.pairnum()==0){list2.add(r);}
		}
		return list2;
	}
	
	@Deprecated
	//No longer needed
	public int countClumps(ArrayList<Read> list){
		int count=0;
		long currentKmer=-1;
		for(final Read r : list){
			final ReadKey key=(ReadKey)r.obj;
			if(key.kmer!=currentKmer){
				currentKmer=key.kmer;
				count++;
			}
		}
		return count;
	}
	
	public ArrayList<FetchThread> fetchReads(final KmerComparator kc, final int fetchThreads, SynchronousQueue<ArrayList<Read>> listQ, ConcurrentReadOutputStream[] rosa){
		AtomicInteger nextGroup=new AtomicInteger(0);
		if(verbose){outstream.println("Making "+fetchThreads+" fetch thread"+(fetchThreads==1 ? "." : "s."));}
		ArrayList<FetchThread> alft=new ArrayList<FetchThread>(fetchThreads);
		for(int i=0; i<fetchThreads; i++){alft.add(new FetchThread(kc, listQ, nextGroup, rosa));}
		
		if(verbose){outstream.println("Starting threads.");}
		for(FetchThread ft : alft){ft.start();}
		
		assert(alft.size()==fetchThreads);
		
		return alft;
	}
	

	private long closeFetchThreads(ArrayList<FetchThread> alft){
		readsThisPass=0;
		memThisPass=0;
		/* Wait for threads to die */
		for(FetchThread ft : alft){

			/* Wait for a thread to die */
			while(ft.getState()!=Thread.State.TERMINATED){
				try {
					ft.join();
				} catch (InterruptedException e) {
					e.printStackTrace();
				}
			}
			readsThisPass+=ft.readsProcessedT;
			basesProcessed+=ft.basesProcessedT;
			diskProcessed+=ft.diskProcessedT;
			memThisPass+=ft.memProcessedT;
			
			errorState|=ft.errorStateT;
		}
		readsProcessed+=readsThisPass;
		memProcessed+=memThisPass;
		
		ecco=false;
		return readsThisPass;
	}
	
	public ArrayList<Read> processClumps(ClumpList cl, int mode){
		long[] rvector=new long[2];
		ArrayList<Read> out=cl.process(Shared.threads(), mode, rvector);
		correctionsThisPass=rvector[0];
		correctionsTotal+=correctionsThisPass;
		duplicatesThisPass=rvector[1];
		duplicatesTotal+=duplicatesThisPass;
		cl.clear();
		return out;
	}
	
	/*--------------------------------------------------------------*/
	/*----------------         Inner Methods        ----------------*/
	/*--------------------------------------------------------------*/
	
	/*--------------------------------------------------------------*/
	/*----------------         Inner Classes        ----------------*/
	/*--------------------------------------------------------------*/
	
	private class FetchThread extends Thread{
		
		FetchThread(final KmerComparator kc_, SynchronousQueue<ArrayList<Read>> listQ_, AtomicInteger nextGroup_, ConcurrentReadOutputStream[] rosa_){
			kc=kc_;
			listQ=listQ_;
			nextGroup=nextGroup_;
			rosa=rosa_;
		}
		
		@Override
		public void run(){
			for(ArrayList<Read> reads=fetchNext(); reads!=null; reads=fetchNext()){
				boolean success=false;
				while(!success){
					try {
						listQ.put(reads);
						success=true;
					} catch (InterruptedException e) {
						// TODO Auto-generated catch block
						e.printStackTrace();
					}
				}
			}
			if(verbose){System.err.println("No more reads to fetch.");}
			boolean success=false;
			while(!success){
				try {
					if(verbose){System.err.println("Adding poison.");}
					listQ.put(POISON);
					success=true;
				} catch (InterruptedException e) {
					// TODO Auto-generated catch block
					e.printStackTrace();
				}
			}
			if(verbose){System.err.println("A fetch thread finished.");}
		}
		
		private ArrayList<Read> fetchNext(){
			final int group=nextGroup.getAndIncrement();
			if(group>=groups){return null;}
			
//			assert(false) : ffin1[group]+", "+FASTQ.FORCE_INTERLEAVED+", "+FASTQ.TEST_INTERLEAVED;
			
			final ConcurrentReadInputStream cris=ConcurrentReadInputStream.getReadInputStream(maxReads, false, ffin1[group], ffin2[group], null, null);
			cris.start();
			
			//Check for file size imbalance
			if(!Clump.forceProcess){
				final long size;
				double expectedMem;
				{
					size=new File(ffin1[group].name()).length()+(ffin2[group]==null ? 0 : new File(ffin2[group].name()).length());
//					expectedMem=size*(ffin1[group].compressed() ? 40 : 8);
					expectedMem=(fileMem*(double)size)/fileSize;
				}
				
				if(size-1000>expectedSizePerGroup*3 || expectedMem*3>totalMem){
					
//					outstream.println("size="+size+", expectedSizePerGroup="+expectedSizePerGroup+", expectedMem="+expectedMem+", expectedSizePerGroup="+expectedMemPerGroup);
//					outstream.println("totalMem="+totalMem+", Shared.memFree()="+Shared.memFree()+", Shared.memUsed()="+Shared.memUsed());
//					outstream.println("fileSize="+fileSize+", "+"fileMem="+fileMem+", "+"memRatio="+memRatio+", ");
//					outstream.println((size-1000>expectedSizePerGroup*3)+", "+(expectedMem*3>totalMem));
					
					//TODO: This could also be based on the number of FetchThreads.
					
					if(expectedMem>0.3*totalMem && (fileMem<1 || fileMem>0.8*totalMem)){
						outstream.println("\n***  Warning  ***\n"
								+ "A temp file may be too big to store in memory, due to uneven splitting:");

						outstream.println("expectedMem="+((long)expectedMem)+", fileMem="+fileMem+", available="+totalMem);

						if(repair || namesort){
							outstream.println("It cannot be streamed to output unaltered because "+(namesort ? " namesort=t" : "repair=t"));
							outstream.println("If this causes the program to crash, please re-run with more memory or groups.\n");
						}else{
							outstream.println(
									"It will be streamed to output unaltered.\n"
											+ "To avoid this behavior, increase memory or increase groups.\n"
											+ "Set the flag forceprocess=t to disable this check.\n");
//							Timer t=new Timer();
							ArrayList<Read> list=streamNext_inner(cris);
//							t.stop("Stream time: ");
							return list;
						}
					}
				}
			}
			
			return fetchNext_inner(cris);
		}
		
		private ArrayList<Read> streamNext_inner(ConcurrentReadInputStream cris){
			StreamToOutput sto=new StreamToOutput(cris, rosa, kc, (repair || namesort));
			errorStateT|=sto.process();
			return new ArrayList<Read>();
		}
		
		private ArrayList<Read> fetchNext_inner(ConcurrentReadInputStream cris){
			
//			Timer t=new Timer();
//			if(verbose){t.start("Making hash threads.");}
			final int subthreads=Tools.mid(1, Shared.threads()/2, 8);
			ArrayList<FetchSubThread> alht=new ArrayList<FetchSubThread>(subthreads);
			long readsThisGroup=0, memThisGroup=0;
			for(int i=0; i<subthreads; i++){alht.add(new FetchSubThread(i, cris, kc, unpair));}
			
//			if(verbose){outstream.println("Starting threads.");}
			for(FetchSubThread ht : alht){ht.start();}
			
//			if(verbose){outstream.println("Waiting for threads.");}
			/* Wait for threads to die */
			for(FetchSubThread ht : alht){
				
				/* Wait for a thread to die */
				while(ht.getState()!=Thread.State.TERMINATED){
					try {
						ht.join();
					} catch (InterruptedException e) {
						e.printStackTrace();
					}
				}
				readsThisGroup+=ht.readsProcessedST;
				basesProcessedT+=ht.basesProcessedST;
				diskProcessedT+=ht.diskProcessedST;
				memThisGroup+=ht.memProcessedST;
			}
			readsProcessedT+=readsThisGroup;
			memProcessedT+=memThisGroup;

//			if(verbose){t.stop("Hash time: ");}
//			if(verbose){System.err.println("Closing input stream.");}
			errorStateT=ReadWrite.closeStream(cris)|errorStateT;
			
//			if(verbose){t.start("Combining thread output.");}
			assert(readsProcessedT<=Integer.MAX_VALUE && readsProcessedT>=0);
			ArrayList<Read> list=new ArrayList<Read>((int)readsThisGroup);
			for(int i=0; i<subthreads; i++){
				FetchSubThread ht=alht.set(i, null);
				list.addAll(ht.storage);
			}
			assert(list.size()==readsThisGroup || (cris.paired() && list.size()*2==readsThisGroup)) : list.size()+", "+readsThisGroup+", "+cris.paired();
//			if(verbose){t.stop("Combine time: ");}
			
//			if(verbose){t.start("Sorting.");}
			Shared.sort(list, kc);
			
//			if(verbose){t.stop("Sort time: ");}
			return list;
		}
		
		final SynchronousQueue<ArrayList<Read>> listQ;
		final AtomicInteger nextGroup;
		final KmerComparator kc;
		final ConcurrentReadOutputStream[] rosa;
		
		protected long readsProcessedT=0;
		protected long basesProcessedT=0;
		protected long diskProcessedT=0;
		protected long memProcessedT=0;
		protected boolean errorStateT=false;
		
		
		private class FetchSubThread extends Thread{
			
			FetchSubThread(int id_, ConcurrentReadInputStream cris_, KmerComparator kc_, boolean unpair_){
				id=id_;
				cris=cris_;
				kcT=kc_;
				storage=new ArrayList<Read>();
				unpairT=unpair_;
			}
			
			@Override
			public void run(){
				ListNum<Read> ln=cris.nextList();
				final boolean paired=cris.paired();
				ArrayList<Read> reads=(ln!=null ? ln.list : null);
				
				while(reads!=null && reads.size()>0){
					
					for(Read r : reads){
						if(!r.validated()){
							r.validate(true);
							if(r.mate!=null){r.mate.validate(true);}
						}
						readsProcessedST+=1+r.mateCount();
						basesProcessedST+=r.length()+r.mateLength();
						diskProcessedST+=r.countFastqBytes()+r.countMateFastqBytes();
						memProcessedST+=r.countBytes()+r.countMateBytes()+ReadKey.overhead;
						if(shrinkName){
							Clumpify.shrinkName(r);
							Clumpify.shrinkName(r.mate);
						}else if(shortName){
							Clumpify.shortName(r);
							Clumpify.shortName(r.mate);
						}
					}
					
					if(ecco){
						for(Read r : reads){
							Read r2=r.mate;
							assert(r.obj==null) : "TODO: Pivot should not have been generated yet, though it may be OK.";
							assert(r2!=null) : "ecco requires paired reads.";
							if(r2!=null){
								int x=BBMerge.findOverlapStrict(r, r2, true);
								if(x>=0){
									r.obj=null;
									r2.obj=null;
								}
							}
						}
					}
					
					ArrayList<Read> hashList=reads;
					if(paired && unpairT){
						hashList=new ArrayList<Read>(reads.size()*2);
						for(Read r1 : reads){
							Read r2=r1.mate;
							assert(r2!=null);
							hashList.add(r1);
							hashList.add(r2);
							if(groups>1 || !repair || namesort){
								r1.mate=null;
								r2.mate=null;
							}
						}
					}
					
					kcT.hash(hashList, table, minCount, true);
					storage.addAll(hashList);
					cris.returnList(ln.id, ln.list.isEmpty());
					ln=cris.nextList();
					reads=(ln!=null ? ln.list : null);
				}
				if(ln!=null){
					cris.returnList(ln.id, ln.list==null || ln.list.isEmpty());
				}
				
				//Optimization for TimSort
				if(parallelSort){
					storage.sort(kcT);
//					Shared.sort(storage, kc); //Already threaded; this is not needed.
				}else{
					Collections.sort(storage, kcT);
				}
			}

			final int id;
			final ConcurrentReadInputStream cris;
			final KmerComparator kcT;
			final ArrayList<Read> storage;
			final boolean unpairT;

			protected long readsProcessedST=0;
			protected long basesProcessedST=0;
			protected long diskProcessedST=0;
			protected long memProcessedST=0;
		}
		
	}
	
	/*--------------------------------------------------------------*/
	/*----------------            Fields            ----------------*/
	/*--------------------------------------------------------------*/

	private int k=31;
	int minCount=0;
	
	int groups=1;
	
	KCountArray table=null;
	
	/*--------------------------------------------------------------*/
	/*----------------          I/O Fields          ----------------*/
	/*--------------------------------------------------------------*/

	private String in1=null;
	private String in2=null;

	private String out1=null;
	private String out2=null;
	
	private String extin=null;
	private String extout=null;
	
	/*--------------------------------------------------------------*/
	
	protected long readsProcessed=0;
	protected long basesProcessed=0;
	protected long diskProcessed=0;
	protected long memProcessed=0;

	protected long readsOut=0;
	protected long basesOut=0;

	protected long readsThisPass=0;
	protected long memThisPass=0;
	
	protected static long lastMemProcessed=0;
	
	protected long readsProcessedThisPass=0;
	protected long clumpsProcessedThisPass=0;
	protected long correctionsThisPass=0;
	
	protected long duplicatesThisPass=0;
	protected static long duplicatesTotal=0;
	
	protected long clumpsProcessedTotal=0;
	protected static long correctionsTotal=0;

	protected int passes=1;
	
	long maxReads=-1;
	private boolean addName=false;
	boolean shortName=false;
	boolean shrinkName=false;
	private boolean rcomp=false;
	private boolean condense=false;
	private boolean correct=false;
	private boolean dedupe=false;
	private boolean splitInput=false;
	boolean ecco=false;
	boolean unpair=false;
	boolean repair=false;
	boolean namesort=false;
	
	final boolean parallelSort=Shared.parallelSort;
	
	final long expectedSizePerGroup;
	private final long expectedMemPerGroup;
	final long totalMem;
	final long fileMem;
	final long fileSize;
	
	private final int outerPassNum;
	private final int outerPasses;
	
	private final double memRatio;
	
	/*--------------------------------------------------------------*/

	static final ArrayList<Read> POISON=new ArrayList<Read>();
	protected static int fetchThreads=2;
	
	/*--------------------------------------------------------------*/
	/*----------------         Final Fields         ----------------*/
	/*--------------------------------------------------------------*/

	final FileFormat ffin1[];
	final FileFormat ffin2[];

	private final FileFormat ffout1[];
	private final FileFormat ffout2[];
	
	/*--------------------------------------------------------------*/
	/*----------------        Common Fields         ----------------*/
	/*--------------------------------------------------------------*/
	
	PrintStream outstream=System.err;
	public static boolean verbose=true;
	public static boolean doHashAndSplit=true;
	public boolean errorState=false;
	private boolean overwrite=false;
	private boolean append=false;
	
}
