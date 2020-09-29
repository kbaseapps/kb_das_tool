package var2;

import shared.Tools;

public class VarFilter {
	

	public boolean parse(String a, String b, String arg){
		if(a.equals("minreads")){
			minReads=Integer.parseInt(b);
		}else if(a.equals("minqualitymax") || a.equals("minmaxquality")){
			minMaxQuality=Integer.parseInt(b);
		}else if(a.equals("minedistmax") || a.equals("minmaxedist")){
			minMaxEdist=Integer.parseInt(b);
		}else if(a.equals("minmapqmax") || a.equals("minmaxmapq")){
			minMaxMapq=Integer.parseInt(b);
		}else if(a.equals("minidmax") || a.equals("minmaxid")){
			minMaxIdentity=Double.parseDouble(b);
			if(minMaxIdentity>1){minMaxIdentity/=100;}
		}

		else if(a.equals("minpairingrate") || a.equals("minpairrate")){
			minPairingRate=Double.parseDouble(b);
		}else if(a.equals("minstrandratio")){
			minStrandRatio=Double.parseDouble(b);
		}else if(a.equals("minscore")){
			minScore=Double.parseDouble(b);
		}else if(a.equals("maxscore")){
			maxScore=Double.parseDouble(b);
		}else if(a.equals("minquality") || a.equals("minavgquality") || a.equals("maq")){
			minAvgQuality=Double.parseDouble(b);
		}else if(a.equals("maxquality") || a.equals("maxavgquality")){
			maxAvgQuality=Double.parseDouble(b);
		}else if(a.equals("minedist") || a.equals("minavgedist") || a.equals("mae")){
			minAvgEdist=Double.parseDouble(b);
		}else if(a.equals("minavgmapq")){
			minAvgMapq=Double.parseDouble(b);
		}else if(a.equals("maxavgmapq")){
			maxAvgMapq=Double.parseDouble(b);
		}else if(a.equals("minallelefraction") || a.equals("minallelefrequency") || a.equals("maf")){
			minAlleleFraction=Double.parseDouble(b);
		}else if(a.equals("maxallelefraction") || a.equals("maxallelefrequency")){
			maxAlleleFraction=Double.parseDouble(b);
		}else if(a.equals("minidentity") || a.equals("mid") || a.equals("minid")){
			minIdentity=Double.parseDouble(b);
			if(minIdentity>1){minIdentity/=100;}
		}else if(a.equals("maxidentity") || a.equals("maxid")){
			maxIdentity=Double.parseDouble(b);
			if(maxIdentity>1 && maxIdentity<=100){maxIdentity/=100;}
		}else if(a.equals("lowcoveragepenalty") || a.equals("lowcovpenalty") || a.equals("covpenalty")){
			Var.lowCoveragePenalty=Double.parseDouble(b);
			assert(Var.lowCoveragePenalty>=0) : "Low coverage penalty must be at least 0.";
		}
		
		else if(a.equals("rarity")){
			rarity=Double.parseDouble(b);
			assert(rarity>=0 && rarity<=1);
			minAlleleFraction=Tools.min(minAlleleFraction, rarity);
		}
		
		else if(a.equals("clearfilters")){
			if(Tools.parseBoolean(b)){clear();}
		}else{
			return false;
		}
		return true;
	}
	
	public void clear(){
		minReads=0;
		minMaxQuality=0;
		minMaxEdist=0;
		minMaxMapq=0;
		minMaxIdentity=0;

		minPairingRate=0;
		minStrandRatio=0;
		minScore=0;
		minAvgQuality=0;
		minAvgEdist=0;
		minAvgMapq=0;
		minAlleleFraction=0;
		minIdentity=0;
		
		maxScore=Integer.MAX_VALUE;
		maxAvgQuality=Integer.MAX_VALUE;
		maxAvgMapq=Integer.MAX_VALUE;
		maxAlleleFraction=Integer.MAX_VALUE;
		maxIdentity=Integer.MAX_VALUE;
	}
	

	public void setFrom(VarFilter filter) {
		minReads=filter.minReads;
		minMaxQuality=filter.minMaxQuality;
		minMaxEdist=filter.minMaxEdist;
		minMaxMapq=filter.minMaxMapq;
		minMaxIdentity=filter.minMaxIdentity;

		minPairingRate=filter.minPairingRate;
		minStrandRatio=filter.minStrandRatio;
		minScore=filter.minScore;
		minAvgQuality=filter.minAvgQuality;
		minAvgEdist=filter.minAvgEdist;
		minAvgMapq=filter.minAvgMapq;
		minAlleleFraction=filter.minAlleleFraction;
		minIdentity=filter.minIdentity;

		maxScore=filter.maxScore;
		maxAvgQuality=filter.maxAvgQuality;
		maxAvgMapq=filter.maxAvgMapq;
		maxAlleleFraction=filter.maxAlleleFraction;
		maxIdentity=filter.maxIdentity;
	}
	
	public boolean passesFast(Var v){
		if(v.count()<minReads){return false;}
		if(v.baseQMax<minMaxQuality){return false;}
		if(v.endDistMax<minMaxEdist){return false;}
		if(v.mapQMax<minMaxMapq){return false;}
		return true;
	}
	
	public boolean passesFilter(Var v, double pairingRate, double totalQualityAvg, double totalMapqAvg, double readLengthAvg, int ploidy, ScafMap map){
		final int count=v.count();
		if(count<minReads){return false;}
		if(v.baseQMax<minMaxQuality){return false;}
		if(v.endDistMax<minMaxEdist){return false;}
		if(v.mapQMax<minMaxMapq){return false;}
		if(v.idMax*0.001f<minMaxIdentity){return false;}

		//Slower, uses division.
//		if(pairingRate>0 && minPairingRate>0 && v.pairingRate()<minPairingRate){return false;}
//		if(minStrandRatio>0 && v.strandRatio()<minStrandRatio){return false;}
//		if(minAvgQuality>0 && v.baseQAvg()<minAvgQuality){return false;}
//		if(minAvgEdist>0 && v.edistAvg()<minAvgEdist){return false;}
//		if(minAvgMapq>0 && v.mapQAvg()<minAvgMapq){return false;}

		if(pairingRate>0 && minPairingRate>0 && count*minPairingRate>v.properPairCount){return false;}
		if(minAvgQuality>0 && count*minAvgQuality>v.baseQSum){return false;}
		if(minAvgEdist>0 && count*minAvgEdist>v.endDistSum){return false;}
		if(minAvgMapq>0 && count*minAvgMapq>v.mapQSum){return false;}
		if(minIdentity>0 && count*minIdentity*1000>v.idSum){return false;}
		
		if(maxAvgQuality<Integer.MAX_VALUE && count*maxAvgQuality<v.baseQSum){return false;}
		if(maxAvgMapq<Integer.MAX_VALUE && count*maxAvgMapq<v.mapQSum){return false;}
		if(maxIdentity<Integer.MAX_VALUE && count*maxIdentity*1000<v.idSum){return false;}
		
		if(minStrandRatio>0 && v.strandRatio()<minStrandRatio){return false;}
		
		if(minAlleleFraction>0 && v.coverage()>0){
			final double af=v.revisedAlleleFraction==-1 ? v.alleleFraction() : v.revisedAlleleFraction;
			if(af<minAlleleFraction){return false;}
		}
		if(maxAlleleFraction<Integer.MAX_VALUE && v.coverage()>0){
			final double af=v.revisedAlleleFraction==-1 ? v.alleleFraction() : v.revisedAlleleFraction;
			if(af>maxAlleleFraction){return false;}
		}
		
		if(minScore>0 || maxScore<Integer.MAX_VALUE){
			double phredScore=v.phredScore(pairingRate, totalQualityAvg, totalMapqAvg, readLengthAvg, rarity, ploidy, map);
			if(phredScore<minScore || phredScore>maxScore){return false;}
		}
		
		return true;
	}
	
	public String toString(double pairingRate, int ploidy){
		StringBuilder sb=new StringBuilder();
		
		sb.append("pairingRate=").append(pairingRate).append("\n");
		sb.append("ploidy=").append(ploidy).append("\n");
		
		sb.append("minReads=").append(minReads).append("\n");
		sb.append("minMaxQuality=").append(minMaxQuality).append("\n");
		sb.append("minMaxEdist=").append(minMaxEdist).append("\n");
		sb.append("minMaxMapq=").append(minMaxMapq).append("\n");
		sb.append("minMaxIdentity=").append(minMaxIdentity).append("\n");
		
		sb.append("minPairingRate=").append(minPairingRate).append("\n");
		sb.append("minStrandRatio=").append(minStrandRatio).append("\n");
		sb.append("minScore=").append(minScore).append("\n");
		sb.append("minAvgQuality=").append(minAvgQuality).append("\n");
		sb.append("minAvgEdist=").append(minAvgEdist).append("\n");
		sb.append("minAvgMapq=").append(minAvgMapq).append("\n");
		sb.append("minAlleleFraction=").append(minAlleleFraction);
		sb.append("minIdentity=").append(minIdentity);
		
		return sb.toString();
	}
	
	public int minReads=2;
	public int minMaxQuality=15;
	public int minMaxEdist=20;
	public int minMaxMapq=0;
	public double minMaxIdentity=0;
	
	public double minPairingRate=0.1;
	public double minStrandRatio=0.1;
	public double minScore=20;
	public double maxScore=Integer.MAX_VALUE;
	public double minAvgQuality=12;
	public double maxAvgQuality=Integer.MAX_VALUE;
	public double minAvgEdist=10;
	public double minAvgMapq=0;
	public double maxAvgMapq=Integer.MAX_VALUE;
	public double minAlleleFraction=0.1;
	public double maxAlleleFraction=Integer.MAX_VALUE;
	public double minIdentity=0;
	public double maxIdentity=Integer.MAX_VALUE;
	public double rarity=1;
	
}
