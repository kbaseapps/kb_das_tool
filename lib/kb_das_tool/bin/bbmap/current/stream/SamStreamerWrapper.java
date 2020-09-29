package stream;

import java.util.ArrayList;
import java.util.Locale;

import fileIO.FileFormat;
import fileIO.ReadWrite;
import shared.Parser;
import shared.PreParser;
import shared.Shared;
import shared.Timer;
import shared.Tools;
import structures.ListNum;
import var2.SamFilter;

public class SamStreamerWrapper {
	
	/**
	 * Code entrance from the command line.
	 * @param args Command line arguments
	 */
	public static void main(String[] args){
		//Start a timer immediately upon code entrance.
		Timer t=new Timer();

		//Create an instance of this class
		SamStreamerWrapper x=new SamStreamerWrapper(args);

		//Run the object
		x.process(t);
		
		//Close the print stream if it was redirected
		Shared.closeStream(x.outstream);
	}

	SamStreamerWrapper(String[] args){
		
		{//Preparse block for help, config files, and outstream
			PreParser pp=new PreParser(args, getClass(), false);
			args=pp.args;
			outstream=pp.outstream;
		}
		
		Shared.capBuffers(4);
		ReadWrite.USE_PIGZ=ReadWrite.USE_UNPIGZ=true;
		ReadWrite.MAX_ZIP_THREADS=Shared.threads();
		ReadStreamByteWriter.USE_ATTACHED_SAMLINE=true;
		
		filter=new SamFilter();
		boolean doFilter=true;
		
		Parser parser=new Parser();
		for(int i=0; i<args.length; i++){
			String arg=args[i];
			String[] split=arg.split("=");
			String a=split[0].toLowerCase();
			String b=split.length>1 ? split[1] : null;

			if(a.equals("verbose")){
				verbose=Tools.parseBoolean(b);
			}else if(a.equals("ordered")){
				ordered=Tools.parseBoolean(b);
			}
			
			//Filter
			else if(a.equals("filter")){
				doFilter=Tools.parseBoolean(b);
			}else if(filter.parse(arg, a, b)){
				//do nothing
			}
				
			else if(parser.parse(arg, a, b)){
				//do nothing
			}else{
				outstream.println("Unknown parameter "+args[i]);
				assert(false) : "Unknown parameter "+args[i];
				//				throw new RuntimeException("Unknown parameter "+args[i]);
			}
		}
		if(!doFilter){filter=null;}

		{//Process parser fields
			Parser.processQuality();

			maxReads=parser.maxReads;
			in1=parser.in1;
			out1=parser.out1;
		}

		ffout1=FileFormat.testOutput(out1, FileFormat.FASTQ, null, true, true, false, true);
		ffin1=FileFormat.testInput(in1, FileFormat.SAM, null, true, true);
		
		if(ffout1==null || !ffout1.samOrBam()){
			SamLine.PARSE_2=false;
			SamLine.PARSE_5=false;
			SamLine.PARSE_6=false;
			SamLine.PARSE_7=false;
			SamLine.PARSE_8=false;
			SamLine.PARSE_OPTIONAL=false;
		}
	}
	
	void process(Timer t){
		
		boolean useSharedHeader=(ffout1!=null && ffout1.samOrBam());
		final SamReadStreamer ss=new SamReadStreamer(ffin1, ordered ? 1 : SamStreamer.DEFAULT_THREADS, useSharedHeader);
		ss.start();

		final ConcurrentReadOutputStream ros;
		if(out1!=null){
			final int buff=4;
			ros=ConcurrentReadOutputStream.getStream(ffout1, null, buff, null, useSharedHeader);
			ros.start();
		}else{ros=null;}

		long readsProcessed=0, readsOut=0;
		long basesProcessed=0, basesOut=0;
		for(ListNum<Read> ln=ss.nextReads(); ln!=null && ln.size()>0; ln=ss.nextReads()){
			ArrayList<Read> list=ln.list;
			if(verbose){outstream.println("Got list of size "+ln.size());}
			ArrayList<Read> out=new ArrayList<Read>(list.size());
			for(Read r : list){
				final int len=r.length();
				readsProcessed++;
				basesProcessed+=len;
				SamLine sl=(SamLine) r.obj;
				boolean keep=filter==null || filter.passesFilter(sl);
				if(keep){
					out.add(r);
					readsOut++;
					basesOut+=len;
				}
			}
			if(ros!=null){ros.add(out, ln.id);}
		}
		
		errorState|=ss.errorState;
		errorState|=ReadWrite.closeStream(ros);
		if(verbose){outstream.println("Finished.");}
		
		t.stop();
		outstream.println("Time:                         \t"+t);
		outstream.println("Reads Processed:    "+readsProcessed+" \t"+String.format(Locale.ROOT, "%.2fk reads/sec", (readsProcessed/(double)(t.elapsed))*1000000));
		outstream.println("Bases Processed:    "+basesProcessed+" \t"+String.format(Locale.ROOT, "%.2f Mbp/sec", (basesProcessed/(double)(t.elapsed))*1000));
		outstream.println("Reads Out:          "+readsOut);
		outstream.println("Bases Out:          "+basesOut);
		
		/* Throw an exception if errors were detected */
		if(errorState){
			throw new RuntimeException(getClass().getSimpleName()+" terminated in an error state; the output may be corrupt.");
		}
	}
	
	/*--------------------------------------------------------------*/
	
	/*--------------------------------------------------------------*/
	/*----------------            Fields            ----------------*/
	/*--------------------------------------------------------------*/
	
	SamFilter filter;
	
	private String in1=null;
	private String out1=null;
	
	private final FileFormat ffin1;
	private final FileFormat ffout1;
	
	/*--------------------------------------------------------------*/

	public boolean errorState=false;
	public boolean ordered=true;
	private long maxReads=-1;
	
	/*--------------------------------------------------------------*/
	
	private java.io.PrintStream outstream=System.err;
	public static boolean verbose=false;
	
}
