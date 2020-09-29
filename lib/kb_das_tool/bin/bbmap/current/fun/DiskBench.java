package fun;

import java.io.File;
import java.io.PrintStream;
import java.util.Random;
import fileIO.ByteFile;
import fileIO.ByteStreamWriter;
import fileIO.FileFormat;
import fileIO.ReadWrite;
import shared.Parser;
import shared.PreParser;
import shared.Shared;
import shared.Timer;
import shared.Tools;
import stream.ByteBuilder;
import stream.FastaReadInputStream;

/**
 * @author Brian Bushnell
 * @date December 6, 2017
 *
 */
public class DiskBench {
	
	public static void main(String[] args){
		//Start a timer immediately upon code entrance.
		Timer t=new Timer();
		
		//Create an instance of this class
		DiskBench x=new DiskBench(args);
		
		//Run the object
		x.process(t);
		
		//Close the print stream if it was redirected
		Shared.closeStream(x.outstream);
	}
	
	public DiskBench(String[] args){
		
		{//Preparse block for help, config files, and outstream
			PreParser pp=new PreParser(args, getClass(), false);
			args=pp.args;
			outstream=pp.outstream;
		}
		
		ReadWrite.USE_PIGZ=ReadWrite.USE_UNPIGZ=true;
		ReadWrite.MAX_ZIP_THREADS=Shared.threads();
		
		Parser parser=new Parser();
		parser.overwrite=true;
		for(int i=0; i<args.length; i++){
			String arg=args[i];
			String[] split=arg.split("=");
			String a=split[0].toLowerCase();
			String b=split.length>1 ? split[1] : null;

			if(a.equals("path")){
				path=b;
				if(path==null){path="";}
				else if(!path.endsWith("/")){path=path+"/";}
			}else if(a.equals("lines")){
				maxLines=Long.parseLong(b);
				if(maxLines<0){maxLines=Long.MAX_VALUE;}
			}else if(a.equals("data") || a.equals("size")){
				data=Tools.parseKMG(b);
			}else if(a.equals("passes")){
				passes=Integer.parseInt(b);
			}else if(a.equals("verbose")){
				verbose=Tools.parseBoolean(b);
			}else if(a.equals("gzip")){
				verbose=Tools.parseBoolean(b);
			}else if(a.equals("mode")){
				if(Tools.isDigit(b.charAt(0))){mode=Integer.parseInt(b);}
				else if("read".equalsIgnoreCase(b) || "r".equalsIgnoreCase(b)){mode=READ;}
				else if("write".equalsIgnoreCase(b) || "w".equalsIgnoreCase(b)){mode=WRITE;}
				else if("readwrite".equalsIgnoreCase(b) || "rw".equalsIgnoreCase(b)){mode=READWRITE;}
				else{assert(false) : "Bad mode: "+arg;}
			}else if(a.equals("read") || a.equals("r")){
				mode=READ;
			}else if(a.equals("write") || a.equals("w")){
				mode=WRITE;
			}else if(a.equals("readwrite") || a.equals("rw")){
				mode=READWRITE;
			}else if(parser.parse(arg, a, b)){
				//do nothing
			}else{
				outstream.println("Unknown parameter "+args[i]);
				assert(false) : "Unknown parameter "+args[i];
				//				throw new RuntimeException("Unknown parameter "+args[i]);
			}
		}
		
		{//Process parser fields
			overwrite=parser.overwrite;
			threads=Shared.threads();
		}
		
		assert(FastaReadInputStream.settingsOK());
		
		if(!ByteFile.FORCE_MODE_BF2){
			ByteFile.FORCE_MODE_BF2=false;
			ByteFile.FORCE_MODE_BF1=true;
		}

		File pfile=new File(path);
		if(!pfile.exists()){pfile.mkdirs();}
	}
	
	class WriteThread extends Thread{
		
		public WriteThread(String fname_, long size_){
			fname=fname_;
			size=size_;
		}
		
		@Override
		public void run(){
			t=new Timer();
			written=writeRandomData(fname, size, t, overwrite);
		}
		
		String fname;
		long size;
		long written=0;
		Timer t;
		
	}
	
	public static long writeRandomData(final String fname, final long size, final Timer t, final boolean overwrite){
		if(t!=null){t.start();}
		long written=0;
		final Random randy=Shared.threadLocalRandom();
		FileFormat ffout=FileFormat.testOutput(fname, FileFormat.TEXT, null, true, overwrite, false, false);
		ByteStreamWriter bsw=new ByteStreamWriter(ffout);
		bsw.start();
		final ByteBuilder bb=new ByteBuilder(66000);
		final int shift=6;
		final int shiftsPerRand=32/shift;
		assert(shiftsPerRand>0);
		final long limit=size-20-shiftsPerRand*1000;
		while(written<limit){
			for(int i=0; i<1000; i+=shiftsPerRand){
				int x=randy.nextInt();
				for(int j=0; j<shiftsPerRand; j++){
					byte b=(byte)(33+x&63);
					bb.append(b);
					x>>=shift;
				}
			}
//			for(int i=0; i<1000; i+=shiftsPerRand){
//				long x=randy.nextLong();
//				for(int j=0; j<shiftsPerRand; j++){
//					byte b=(byte)(33+x&63);
//					bb.append(b);
//					x>>=shift;
//				}
//			}
			bb.append('\n');
			written+=bb.length;
			bsw.print(bb);
			bb.clear();
		}
		while(written<size-1){
			bb.append((byte)(33+(randy.nextInt()&63)));
			written++;
		}
		bb.append('\n');
		written+=bb.length;
		bsw.print(bb);
		bb.clear();
		bsw.poisonAndWait();
		File f=new File(fname);
		long diskSize=(f.length());
		if(t!=null){t.stop();}
		return diskSize;
	}
	
	class ReadThread extends Thread{
		
		public ReadThread(String fname_){
			fname=fname_;
		}
		
		@Override
		public void run(){
			t=new Timer();
			FileFormat ffin=FileFormat.testInput(fname, FileFormat.TEXT, null, false, false, false);
			ByteFile bf=ByteFile.makeByteFile(ffin);
			for(byte[] line=bf.nextLine(); line!=null; line=bf.nextLine()){
				read+=line.length+1;
			}
			t.stop();
		}
		
		String fname;
		long read=0;
		Timer t;
		
	}
	
	String[] makeFnames(int pass){
		String[] fnames=new String[threads];
		Random randy=new Random();
		for(int i=0; i<threads; i++){
			fnames[i]=path+pass+"_"+i+"_"+(System.nanoTime()&0xFFFF)+"_"+randy.nextInt(4096);
		}
		return fnames;
	}
	
	Timer readWrite(String[] fnamesW, String[] fnamesR){
		Timer t=new Timer();
		
		WriteThread[] wta=new WriteThread[threads];
		long size=data/threads;
		for(int i=0; i<threads; i++){
			wta[i]=new WriteThread(fnamesW[i], size);
		}
		for(int i=0; i<threads; i++){
			wta[i].start();
		}
		
		ReadThread[] rta=new ReadThread[threads];
		for(int i=0; i<threads; i++){
			rta[i]=new ReadThread(fnamesR[i]);
		}
		for(int i=0; i<threads; i++){
			rta[i].start();
		}
		
		for(int i=0; i<threads; i++){
			while(wta[i].getState()!=Thread.State.TERMINATED){
				try {
					wta[i].join();
				} catch (InterruptedException e) {
					// TODO Auto-generated catch block
					e.printStackTrace();
				}
			}
		}
		
		for(int i=0; i<threads; i++){
			while(rta[i].getState()!=Thread.State.TERMINATED){
				try {
					rta[i].join();
				} catch (InterruptedException e) {
					// TODO Auto-generated catch block
					e.printStackTrace();
				}
			}
		}
		
		t.stop();
		return t;
	}
	
	Timer write(String[] fnames){
		Timer t=new Timer();
		WriteThread[] wta=new WriteThread[threads];
		long size=data/threads;
		for(int i=0; i<threads; i++){
			wta[i]=new WriteThread(fnames[i], size);
		}
		for(int i=0; i<threads; i++){
			wta[i].start();
		}
		for(int i=0; i<threads; i++){
			while(wta[i].getState()!=Thread.State.TERMINATED){
				try {
					wta[i].join();
				} catch (InterruptedException e) {
					// TODO Auto-generated catch block
					e.printStackTrace();
				}
			}
		}
		t.stop();
		return t;
	}
	
	Timer read(String[] fnames){
		Timer t=new Timer();
		ReadThread[] rta=new ReadThread[threads];
		for(int i=0; i<threads; i++){
			rta[i]=new ReadThread(fnames[i]);
		}
		for(int i=0; i<threads; i++){
			rta[i].start();
		}
		for(int i=0; i<threads; i++){
			while(rta[i].getState()!=Thread.State.TERMINATED){
				try {
					rta[i].join();
				} catch (InterruptedException e) {
					// TODO Auto-generated catch block
					e.printStackTrace();
				}
			}
		}
		t.stop();
		return t;
	}
	
	void delete(String[] fnames){
		for(String s : fnames){
			File f=new File(s);
			if(f.exists()){
				f.delete();
			}
		}
	}
	
	void process(Timer t0){
		
		t0.start();
		String[] fnamesW=makeFnames(0);
		
		Timer t=write(fnamesW);
		String[] fnamesR=fnamesW;
		fnamesW=null;
		
		final long initialWriteElapsed=t.elapsed;
		
		System.err.println("Initial write:   \t"+t.toString()+"  \t"+String.format("%.3f MB/s", (1000.0*data)/t.elapsed));
		
		for(int pass=0; pass<passes; pass++){
			if(mode==READWRITE){
				fnamesW=makeFnames(pass);
				t=readWrite(fnamesW, fnamesR);
				delete(fnamesR);
				fnamesR=fnamesW;
				fnamesW=null;
			}else if(mode==READ){
				t=read(fnamesR);
			}else{
				delete(fnamesR);
				fnamesW=makeFnames(pass);
				t=write(fnamesW);
				fnamesR=fnamesW;
				fnamesW=null;
			}
			System.err.println("Pass        "+pass+":   \t"+t.toString()+"  \t"+String.format("%.3f MB/s", (1000.0*data)/t.elapsed));
		}
		delete(fnamesR);
		
		t0.stop();
		System.err.println("Overall:         \t"+t0.toString()+"  \t"+String.format("%.3f MB/s", (1000.0*(data*passes))/(t0.elapsed-initialWriteElapsed)));
		
		if(errorState){
			throw new RuntimeException(getClass().getName()+" terminated in an error state; the output may be corrupt.");
		}
	}
	
	/*--------------------------------------------------------------*/
	
	
	/*--------------------------------------------------------------*/
	
	private String path="";
	
	/*--------------------------------------------------------------*/
	
	private long data=8000000000L;
	private int passes=2;
	
	private int threads;
	
	private long maxLines=Long.MAX_VALUE;
	
	int mode=READWRITE;
	static final int READWRITE=1, READ=2, WRITE=3;
	
	/*--------------------------------------------------------------*/
	
	/*--------------------------------------------------------------*/
	
	private PrintStream outstream=System.err;
	public static boolean verbose=false;
	public boolean errorState=false;
	private boolean overwrite=true;
	
}
