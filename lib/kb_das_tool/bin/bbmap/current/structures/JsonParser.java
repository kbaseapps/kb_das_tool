package structures;

import java.util.ArrayList;

import stream.ByteBuilder;

public class JsonParser {

	public static void main(String[] args){
		String s;
		
		s="{\n"
			+"   \"33154\": {\n"
			+"      \"name\": \"Opisthokonta\",\n"
			+"      \"tax_id\": 33154,\n"
			+"      \"level\": \"no rank\",\n"
			+"      \"no rank\": {\n"
			+"         \"name\": \"Opisthokonta\",\n"
			+"         \"tax_id\": 33154\n"
			+"      },\n"
			+"      \"superkingdom\": {\n"
			+"         \"name\": \"Eukaryota\",\n"
			+"         \"tax_id\": 2759,\n"
			+"         \"number1\": 2759,\n"
			+"         \"number2\": -2759,\n"
			+"         \"number3\": .2759,\n"
			+"         \"number4\": 2.759,\n"
			+"         \"number5\": -2.759,\n"
			+"         \"number6\": -2.759e17,\n"
			+"         \"number7\": -2.759e-1,\n"
			+"         \"number8\": -2.759E-1,\n"
			+"         \"number9\": -2E-1,\n"
			+"         \"slash\": \"hello \\\"world\\\"\",\n"
			+"         \"slash\": \"hello world\",\n"
			+"         \"complex\": [\"hello world\", 1, {\"tax_id\": 2759}, [3, 4, 5]]\n"
			+"      }\n"
			+"   }\n"
			+"}";
		
		s="{\"complex\": [\"a\", 1, {\"b\": 2}, [3, 4, 5]]\n}";
		
		System.out.println("Original:\n"+s);
		JsonParser jp=new JsonParser(s);
		JsonObject j=jp.parseJsonObject();
		System.out.println("Original:\n"+s);
		System.out.println("Regenerated:\n"+j);
		
		s="[\"complex\", 1, {\"b\": 2}, [3, 4, 5]]";
		
		System.out.println("Original:\n"+s);
		jp.set(s.getBytes());
		Object[] array=jp.parseJsonArray();
		System.out.println("Original:\n"+s);
		System.out.println("Regenerated:\n"+JsonObject.toString(array));
	}
	
	public JsonParser(String s){
		set(s.getBytes());
	}
	
	public JsonParser(byte[] s){
		set(s);
	}
	
	public void set(byte[] s){
		text=s;
		pos=0;
	}
	
	public JsonObject parseJsonObject(){
		if(text==null || text.length<1){return null;}
		assert(text[0]=='{') : text[0]+"\n"+new String(text);
		return makeObject();
	}
	
	public Object[] parseJsonArray(){
		if(text==null || text.length<1){return null;}
		assert(text[0]=='[') : text[0]+"\n"+new String(text);
		return makeArray();
	}
	
	private static Object bufferToObject(ByteBuilder bb){
		String s=bb.toString();
		bb.clear();
		final char firstLetter=s.length()>0 ? s.charAt(0) : 0;
		Object value;
		try {
			if(Character.isLetter(firstLetter)){
				if(verbose){System.err.println("Letter");}
				if(s.equalsIgnoreCase("null")){
					value=null;
				}else{
					value=Boolean.parseBoolean(s);
				}
			}else{
				if(verbose){System.err.println("Number");}
				if(s.indexOf('.')>=0 || s.indexOf('e')>=0 || s.indexOf('E')>=0){
					value=Double.parseDouble(s);
				}else{
					value=Long.parseLong(s);
				}
			}
		} catch (Exception e) {
			value="\""+s+"\"";
		}
		return value;
	}
	
	
	private JsonObject makeObject(){
		assert(text[pos]=='{');
		pos++;
		
		if(verbose){System.err.println("Entering makeObject.");}
		
		JsonObject current=new JsonObject();
		ByteBuilder bb=new ByteBuilder();
		boolean quoteMode=false;
		boolean slashMode=false;
		String key=null;
		
		for(; pos<text.length; pos++){
			final byte b=text[pos];
//			if(verbose){System.err.println(pos+"=\t"+(char)b);
			
			if(quoteMode){
				if(slashMode){
					if(verbose){System.err.println(">SlashModeEnd, buffer="+bb);}
					bb.append(b);
					slashMode=false;
				}else if(b=='"'){
					if(verbose){System.err.println(">Quote; quote mode="+quoteMode+", key="+key+", buffer="+bb);}
					String s=bb.toString();
					bb.clear();
					if(key==null){
						key=s;
						if(verbose){System.err.println("Set key to \""+key+"\"");}
					}else{
						current.add(key, s);
						if(verbose){System.err.println("Added \""+key+"\": \""+s+"\"");}
						key=null;
					}
					quoteMode=!quoteMode;
				}else{
					if(verbose){System.err.println(">QuoteMode, buffer="+bb);}
					if(b=='\\'){
						if(verbose){System.err.println(">SlashMode, buffer="+bb);}
						slashMode=true;
					}
					bb.append(b);
				}
			}else if(b=='"'){
				if(verbose){System.err.println(">Quote; quote mode="+quoteMode+", key="+key+", buffer="+bb);}
				quoteMode=!quoteMode;
			}else if(b==','){
				if(verbose){System.err.println(">Comma; key="+key+", buffer=\""+bb+"\""/*+"\n"+new String(text, 0, pos)*/);}
				if(key!=null){//number or boolean
					final Object value=bufferToObject(bb);
					current.add(key, value);
					key=null;
					if(verbose){System.err.println("Added "+value+"; current=\n"+current+"\n");}
				}
			}else if(b==':'){
				if(verbose){System.err.println(">Colon");}
				assert(key!=null);
			}else if(b=='{'){
				if(verbose){System.err.println(">{, key="+key+", current object is:\n"+current+"\n");}
				JsonObject j=makeObject();
				if(key==null){//outermost?
					if(verbose){System.err.println("Returning.");}
					return j;
				}else{
					current.add(key, j);
					if(verbose){System.err.println("Added new object:\n"+j+"\n");}
					key=null;
				}
			}else if(b=='}'){
				if(verbose){System.err.println(">}, key="+key+", current object is:\n"+current+"\n");}
				if(key!=null){//number or boolean
					final Object value=bufferToObject(bb);
					current.add(key, value);
					key=null;
					if(verbose){System.err.println("Added "+value+"; current=\n"+current+"\n");}
				}
				pos++;
				return current; 
			}else if(b=='['){
				if(verbose){System.err.println(">[, current object is:\n"+current+"\n");}
				Object[] array=makeArray();
				assert(key!=null) : "Should be in makeArray.";
				current.add(key, array);
				key=null;
				assert(bb.length()==0);
			}else if(b==']'){
				if(verbose){System.err.println(">], current object is:\n"+current+"\n");}
				assert(false);
			}else if(b==' ' || b=='\t' || b=='\n' || b=='\r'){
				if(verbose){System.err.println(">Other");}
				//ignore
			}else{
				if(verbose){System.err.println(">NormalMode, buffer="+bb);}
				bb.append(b);
			}
		}
		return current;
	}
	
	private Object[] makeArray(){
		assert(text[pos]=='[');
		pos++;
		
		if(verbose){System.err.println("Entering makeArray.");}
		
		ArrayList<Object> current=new ArrayList<Object>();
		ByteBuilder bb=new ByteBuilder();
		boolean quoteMode=false;
		boolean slashMode=false;
		
		for(; pos<text.length; pos++){
			final byte b=text[pos];
			
			if(quoteMode){
				if(slashMode){
					if(verbose){System.err.println(">SlashModeEnd, buffer="+bb);}
					bb.append(b);
					slashMode=false;
				}else if(b=='"'){
					if(verbose){System.err.println(">Quote; quote mode="+quoteMode+", buffer="+bb);}
					String s=bb.toString();
					bb.clear();
					current.add(s);
					quoteMode=!quoteMode;
				}else{
					if(verbose){System.err.println(">QuoteMode, buffer="+bb);}
					if(b=='\\'){
						if(verbose){System.err.println(">SlashMode, buffer="+bb);}
						slashMode=true;
					}
					bb.append(b);
				}
			}else if(b=='"'){
				if(verbose){System.err.println(">Quote; quote mode="+quoteMode+", buffer="+bb);}
				quoteMode=!quoteMode;
			}else if(b==','){
				if(verbose){System.err.println(">Comma; buffer=\""+bb+"\"");}
				if(bb.length()>0){
					final Object value=bufferToObject(bb);
					current.add(value);
					if(verbose){System.err.println("Added "+value+"; current=\n"+current+"\n");}
				}
			}else if(b==':'){
				if(verbose){System.err.println(">Colon");}
				assert(false);
			}else if(b=='{'){
				if(verbose){System.err.println(">{, current object is:\n"+current+"\n");}
				JsonObject j=makeObject();
				current.add(j);
				if(verbose){System.err.println("Added new object:\n"+j+"\n");}
			}else if(b=='}'){
				if(verbose){System.err.println(">}, current array is:\n"+current+"\n");}
				assert(false);
			}else if(b=='['){
				if(verbose){System.err.println(">[, current object is:\n"+current+"\n");}
				Object[] array=makeArray();
				current.add(array);
			}else if(b==']'){
				if(verbose){System.err.println(">], current object is:\n"+current+"\n");}
				if(bb.length()>0){
					final Object value=bufferToObject(bb);
					current.add(value);
					if(verbose){System.err.println("Added "+value+"; current=\n"+current+"\n");}
				}
				if(verbose){System.err.println("Returning "+current+"; text="+new String(text, 0, pos));}
				return current.toArray(); 
			}else if(b==' ' || b=='\t' || b=='\n' || b=='\r'){
				if(verbose){System.err.println(">Other");}
				//ignore
			}else{
				if(verbose){System.err.println(">NormalMode, buffer="+bb);}
				bb.append(b);
			}
		}
		return current.toArray();
	}
	
	byte[] text;
	int pos=0;
	
	private static final boolean verbose=false;
	
}
