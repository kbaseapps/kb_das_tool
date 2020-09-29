package tax;

import java.io.File;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.Locale;

import fileIO.ByteFile;
import fileIO.ByteFile1;
import fileIO.ByteFile2;
import fileIO.ByteStreamWriter;
import fileIO.FileFormat;
import fileIO.ReadWrite;
import kmer.HashArray1D;
import shared.Parser;
import shared.PreParser;
import shared.ReadStats;
import shared.Shared;
import shared.Timer;
import shared.Tools;
import stream.ConcurrentGenericReadInputStream;
import stream.FASTQ;
import stream.FastaReadInputStream;

/**
 * @author Brian Bushnell
 * @date Mar 10, 2015
 *
 */
public class RenameGiToNcbi {
	
	public static void main(String[] args){
		Timer t=new Timer();
		RenameGiToNcbi x=new RenameGiToNcbi(args);
		x.process(t);
		
		//Close the print stream if it was redirected
		Shared.closeStream(x.outstream);
	}
	
	public RenameGiToNcbi(String[] args){
		
		{//Preparse block for help, config files, and outstream
			PreParser pp=new PreParser(args, getClass(), false);
			args=pp.args;
			outstream=pp.outstream;
		}
		
		Shared.capBuffers(4);
		ReadWrite.USE_PIGZ=ReadWrite.USE_UNPIGZ=true;
		ReadWrite.MAX_ZIP_THREADS=Shared.threads();
		FASTQ.TEST_INTERLEAVED=FASTQ.FORCE_INTERLEAVED=false;
		
		Parser parser=new Parser();
		for(int i=0; i<args.length; i++){
			String arg=args[i];
			String[] split=arg.split("=");
			String a=split[0].toLowerCase();
			String b=split.length>1 ? split[1] : null;

			if(a.equals("prefix")){
				prefix=Tools.parseBoolean(b);
			
			}else if(a.equals("title")){
				title=(b==null ? ">" : (">"+b+"|")).getBytes();
			}else if(a.equals("table") || a.equals("gi") || a.equals("gitable")){
				tableFile=b;
				if("auto".equalsIgnoreCase(b)){tableFile=TaxTree.defaultTableFile();}
			}else if(a.equals("accession")){
				accessionFile=b;
				if("auto".equalsIgnoreCase(b)){accessionFile=TaxTree.defaultAccessionFile();}
			}else if(a.equals("tree") || a.equals("taxtree")){
				treeFile=b;
				if("auto".equalsIgnoreCase(b)){treeFile=TaxTree.defaultTreeFile();}
			}else if(a.equals("invalid")){
				outInvalid=b;
			}else if(a.equals("keepall")){
				keepAll=Tools.parseBoolean(b);
			}else if(a.equals("shrinknames")){
				shrinkNames=Tools.parseBoolean(b);
			}else if(a.equals("silva")){
				TaxTree.SILVA_MODE=Tools.parseBoolean(b);
			}else if(a.equals("verbose")){
				verbose=Tools.parseBoolean(b);
				ByteFile1.verbose=verbose;
				ByteFile2.verbose=verbose;
				stream.FastaReadInputStream.verbose=verbose;
				ConcurrentGenericReadInputStream.verbose=verbose;
				stream.FastqReadInputStream.verbose=verbose;
				ReadWrite.verbose=verbose;
			}else if(a.equals("in") || a.equals("in1")){
				if(new File(b).exists()){
					in1.add(b);
				}else{
					for(String bb : b.split(",")){
						in1.add(bb);
					}
				}
			}else if(new File(arg).exists()){ //For asterisk expansion
				in1.add(arg);
			}else if(parser.parse(arg, a, b)){
				//do nothing
			}else{
				outstream.println("Unknown parameter "+args[i]);
				assert(false) : "Unknown parameter "+args[i];
				//				throw new RuntimeException("Unknown parameter "+args[i]);
			}
		}
		
		{//Process parser fields
			Parser.processQuality();
			
			maxReads=parser.maxReads;
			
			overwrite=ReadStats.overwrite=parser.overwrite;
			append=ReadStats.append=parser.append;

			out1=parser.out1;
		}
		
		assert(FastaReadInputStream.settingsOK());
		
		if(in1==null || in1.isEmpty()){throw new RuntimeException("Error - at least one input file is required.");}
		if(!ByteFile.FORCE_MODE_BF1 && !ByteFile.FORCE_MODE_BF2){
			ByteFile.FORCE_MODE_BF2=false;
			ByteFile.FORCE_MODE_BF1=true;
		}

		if(out1!=null && out1.equalsIgnoreCase("null")){out1=null;}
		assert(out1!=null) : "This program requires an output file.";
		
		if(!Tools.testOutputFiles(overwrite, append, false, out1)){
			outstream.println((out1==null)+", "+out1);
			throw new RuntimeException("\n\noverwrite="+overwrite+"; Can't write to output files "+out1+"\n");
		}
		if(!Tools.testInputFiles(false, true, in1.toArray(new String[0]))){
			throw new RuntimeException("\nCan't read to some input files.\n");
		}

		ffout1=FileFormat.testOutput(out1, FileFormat.FA, null, true, overwrite, append, false);
		ffoutInvalid=FileFormat.testOutput(outInvalid, FileFormat.FA, null, true, overwrite, append, false);
		ffin1=new ArrayList<FileFormat>(in1.size());
		for(String s : in1){
			ffin1.add(FileFormat.testInput(s, FileFormat.FA, null, true, true));
		}
		
		if(ffoutInvalid!=null){keepAll=false;}
		
		assert(tableFile!=null || accessionFile!=null || TaxTree.SILVA_MODE) : "No gi or accession information loaded.";
		
		if(treeFile!=null){
			tree=TaxTree.loadTaxTree(treeFile, outstream, true);
			assert(tree.nameMap!=null);
		}else{
			tree=null;
			throw new RuntimeException("No tree specified.");
		}
		
		if(tableFile!=null){
			GiToNcbi.initialize(tableFile);
		}
		if(accessionFile!=null){
			AccessionToTaxid.tree=tree;
			outstream.println("Loading accession table.");
			AccessionToTaxid.load(accessionFile);
//			System.gc();
		}
	}
	
	void process(Timer t){
		
		ByteStreamWriter bsw=(ffout1==null ? null : new ByteStreamWriter(ffout1)); //Actually, this is required.
		if(bsw!=null){bsw.start();}
		
		ByteStreamWriter bswInvalid=null;
		if(ffoutInvalid!=null){
			bswInvalid=new ByteStreamWriter(ffoutInvalid);
			bswInvalid.start();
		}
		
		final HashArray1D counts=(countTable && !prefix) ? new HashArray1D(256000, true) : null;
		
		for(FileFormat ffin : ffin1){
			ByteFile bf=ByteFile.makeByteFile(ffin);
			processInner(bf, bsw, bswInvalid, counts);
		}
		
		if(bsw!=null){errorState|=bsw.poisonAndWait();}
		if(bswInvalid!=null){errorState|=bswInvalid.poisonAndWait();}
		
		t.stop();
		
		double rpnano=readsProcessed/(double)(t.elapsed);
		double bpnano=basesProcessed/(double)(t.elapsed);

		String rpstring=(readsProcessed<100000 ? ""+readsProcessed : readsProcessed<100000000 ? (readsProcessed/1000)+"k" : (readsProcessed/1000000)+"m");
		String bpstring=(basesProcessed<100000 ? ""+basesProcessed : basesProcessed<100000000 ? (basesProcessed/1000)+"k" : (basesProcessed/1000000)+"m");

		while(rpstring.length()<8){rpstring=" "+rpstring;}
		while(bpstring.length()<8){bpstring=" "+bpstring;}
		
		outstream.println("Time:                         \t"+t);
		outstream.println("Reads Processed:    "+rpstring+" \t"+String.format(Locale.ROOT, "%.2fk reads/sec", rpnano*1000000));
		outstream.println("Bases Processed:    "+bpstring+" \t"+String.format(Locale.ROOT, "%.2fm bases/sec", bpnano*1000));
		
		outstream.println();
		outstream.println("Valid Sequences:   \t"+validReads);
		outstream.println("Valid Bases:       \t"+validBases);
		outstream.println("Invalid Sequences: \t"+invalidReads);
		outstream.println("Invalid Bases:     \t"+invalidBases);
		if(counts!=null){
			outstream.println("Unique Taxa:       \t"+taxaCounted);
		}
		
		if(errorState){
			throw new RuntimeException(getClass().getName()+" terminated in an error state; the output may be corrupt.");
		}
	}
	
	private void processInner(ByteFile bf, ByteStreamWriter bsw, ByteStreamWriter bswInvalid, HashArray1D counts){
		
		byte[] line=bf.nextLine();
		boolean valid=false;
		while(line!=null){
			if(line.length>0 && line[0]=='>'){
				readsProcessed++;
				if(maxReads>0 && readsProcessed>maxReads){break;}
				int initial=1, terminal=line.length;
				final TaxNode tn;
				
				{
					{
						//					Handles renumbering when the format is correct but the number is wrong.
						if(Tools.startsWith(line, ">tid|")){
							initial=6;
							while(initial<=line.length && line[initial-1]!='|'){initial++;}
						}else if(Tools.startsWith(line, ">ncbi|")){
							initial=7;
							while(initial<=line.length && line[initial-1]!='|'){initial++;}
						}
					}

					if(shrinkNames){//This is for nr/nt
						for(int i=initial; i<terminal; i++){
							if(line[i]==1){//SOH
								terminal=i;
							}
						}
					}
					
					String s=new String(line, initial, terminal-initial);
					
					tn=tree.parseNodeFromHeader(s, true);
				}
				
//				final int number=GiToNcbi.getID(line);
				final int number=tn==null ? -1 : tn.id;
				valid=(number>=0);
				if(valid){
					validReads++;
					bsw.print(title);
					bsw.print(number);
					if(prefix){
						bsw.print('|');
						for(int i=initial; i<terminal; i++){
							bsw.print(line[i]);
						}
					}else if(counts!=null){
						bsw.print('|');
						int count=counts.increment(number);
						bsw.print(count);
						if(count==1){taxaCounted++;}
					}
					bsw.println();
				}else{
					invalidReads++;
					if(keepAll){
						if(shrinkNames){
							for(int i=0; i<terminal; i++){
								bsw.print(line[i]);
							}
							bsw.println();
						}else{
							bsw.println(line);
						}
					}else if(bswInvalid!=null){
						if(shrinkNames){
							for(int i=0; i<terminal; i++){
								bswInvalid.print(line[i]);
							}
							bswInvalid.println();
						}else{
							bswInvalid.println(line);
						}
					}
				}
			}else{
				basesProcessed+=line.length;
				if(valid || keepAll){
					if(valid){validBases+=line.length;}
					else{invalidBases+=line.length;}
					bsw.println(line);
				}else{
					invalidBases+=line.length;
					if(bswInvalid!=null){
						bswInvalid.println(line);
					}
				}
			}
			line=bf.nextLine();
		}
		
		errorState|=bf.close();
	}
	
	/*--------------------------------------------------------------*/
	
	
	/*--------------------------------------------------------------*/
	
	private LinkedHashSet<String> in1=new LinkedHashSet<String>();
	private String out1=null;
	private String outInvalid=null;

	private String treeFile=null;
	private String tableFile=null;
	private String accessionFile=null;
	
	/*--------------------------------------------------------------*/

	private long maxReads=-1;

	private long validReads=0;
	private long validBases=0;
	private long invalidReads=0;
	private long invalidBases=0;
	private long taxaCounted=0;
	
	private long readsProcessed=0, basesProcessed=0;

	private boolean prefix=true;
	private boolean countTable=true;
	private boolean keepAll=true;
	private boolean shrinkNames=false;
	
	/*--------------------------------------------------------------*/
	
	private final ArrayList<FileFormat> ffin1;
	private final FileFormat ffout1;
	private final FileFormat ffoutInvalid;
	private final TaxTree tree;
	
	/*--------------------------------------------------------------*/
	
	private PrintStream outstream=System.err;
	public static boolean verbose=false;
	public boolean errorState=false;
	private boolean overwrite=false;
	private boolean append=false;
	
	private static byte[] title=">tid|".getBytes();
	
}
