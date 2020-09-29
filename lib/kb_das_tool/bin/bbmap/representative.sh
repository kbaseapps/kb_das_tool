#!/bin/bash

usage(){
echo "
Written by Brian Bushnell
Last modified December 20, 2017

Description:  Makes a representative set of taxa from all-to-all identity
comparison.  Input should be in 3+ column TSV format (first 3 are required):
(query, ref, ANI, qsize, rsize)
...as produced by CompareSketch format=3.  Additional columns are allowed and
will be ignored.

Usage:  representative.sh in=<input file> out=<output file>

Parameters:
overwrite=f     (ow) Set to false to force the program to abort rather than
                overwrite an existing file.
threshold=0     Ignore edges under threshold value.  This also affects the
                choice of centroids; a high threshold gives more weight to 
                higher-value edges.
minratio=0      Ignores edges with a ratio below this value.
invertratio=f   Invert the ratio when greater than 1.
printheader=t   Print a header line in the output.
printsize=t     Print the size of retained nodes.
printclusters=t Print the nodes subsumed by each retained node.

Taxonomy parameters:
level=          Taxonomic level, such as phylum.  Filtering will operate on
                sequences within the same taxonomic level as specified ids.
                If not set, only matches to a node or its descendants will 
                be considered.
ids=            Comma-delimited list of NCBI numeric IDs.  Can also be a
                file with one taxID per line.
names=          Alternately, a list of names (such as 'Homo sapiens').
                Note that spaces need special handling.
include=f       'f' will discard filtered sequences, 't' will keep them.
tree=<file>     Specify a TaxTree file like tree.taxtree.gz.  
                On Genepool, use 'auto'.

Java Parameters:
-Xmx            This will be passed to Java to set memory usage, overriding 
                the program's automatic memory detection.  -Xmx20g will
                specify 20 gigs of RAM, and -Xmx200m will specify 200 megs.
                The max is typically around 85% of physical memory.
-eoom           This flag will cause the process to exit if an out-of-memory
                exception occurs.  Requires Java 8u92+.
-da             Disable assertions.

Please contact Brian Bushnell at bbushnell@lbl.gov if you encounter any problems.
"
}

pushd . > /dev/null
DIR="${BASH_SOURCE[0]}"
while [ -h "$DIR" ]; do
  cd "$(dirname "$DIR")"
  DIR="$(readlink "$(basename "$DIR")")"
done
cd "$(dirname "$DIR")"
DIR="$(pwd)/"
popd > /dev/null

#DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" && pwd )/"
CP="$DIR""current/"

z="-Xmx4g"
z2="-Xms4g"
EA="-ea"
EOOM=""
set=0

if [ -z "$1" ] || [[ $1 == -h ]] || [[ $1 == --help ]]; then
	usage
	exit
fi

calcXmx () {
	source "$DIR""/calcmem.sh"
	parseXmx "$@"
	if [[ $set == 1 ]]; then
		return
	fi
	freeRam 4000m 84
	z="-Xmx${RAM}m"
	z2="-Xms${RAM}m"
}
calcXmx "$@"

a_sample_mt() {
	if [[ $SHIFTER_RUNTIME == 1 ]]; then
		#Ignore NERSC_HOST
		shifter=1
	elif [[ $NERSC_HOST == genepool ]]; then
		module unload oracle-jdk
		module load oracle-jdk/1.8_144_64bit
		module load pigz
	elif [[ $NERSC_HOST == denovo ]]; then
		module unload java
		module load java/1.8.0_144
		module load pigz
	elif [[ $NERSC_HOST == cori ]]; then
		module use /global/common/software/m342/nersc-builds/denovo/Modules/jgi
		module use /global/common/software/m342/nersc-builds/denovo/Modules/usg
		module unload java
		module load java/1.8.0_144
		module load pigz
	fi
	local CMD="java $EA $EOOM $z -cp $CP jgi.RepresentativeSet $@"
	echo $CMD >&2
	eval $CMD
}

a_sample_mt "$@"
