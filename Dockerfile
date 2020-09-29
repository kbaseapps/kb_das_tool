FROM kbase/sdkbase2:python
MAINTAINER Sean Jungbluth <sjungbluth@lbl.gov>
# -----------------------------------------
# In this section, you can install any system dependencies required
# to run your App.  For instance, you could place an apt-get update or
# install line here, a git checkout to download code, or run any other
# installation scripts.

WORKDIR /kb/module/bin

# To install all the dependencies
RUN apt-get update && apt-get install -y wget tzdata git r-base ruby-full gcc zlib1g-dev libtool m4 automake

# To download the CONCOCT software from Github and install it and its requirements

RUN echo "install.packages(\"doMC\", repos=\"https://cran.rstudio.com\")" | R --no-save
RUN echo "install.packages(\"data.table\", repos=\"https://cran.rstudio.com\")" | R --no-save
RUN echo "install.packages(\"ggplot2\", repos=\"https://cran.rstudio.com\")" | R --no-save

RUN git clone https://github.com/bcthomas/pullseq && cd pullseq && ./bootstrap && ./configure && make && make install && cd ../ && rm -rf pullseq

RUN wget https://github.com/hyattpd/Prodigal/releases/download/v2.6.3/prodigal.linux && mv prodigal.linux /usr/local/bin/prodigal && chmod +x /usr/local/bin/prodigal

RUN wget http://github.com/bbuchfink/diamond/releases/download/v0.9.27/diamond-linux64.tar.gz && tar xzf diamond-linux64.tar.gz && mv diamond /usr/local/bin/ && rm diamond-linux64.tar.gz

RUN wget ftp://ftp.ncbi.nlm.nih.gov/blast/executables/blast+/LATEST/*-x64-linux.tar.gz && tar xzf *-x64-linux.tar.gz && sh -c "find . \( -name 'makeblastdb' -o -name 'blastp' \) -exec mv {} /usr/local/bin/ \;" && rm -rf ncbi-blast*

RUN wget https://github.com/cmks/DAS_Tool/archive/1.1.2.tar.gz && tar -xvzf 1.1.2.tar.gz

RUN cd DAS_Tool-1.1.2 && unzip db.zip && chmod 700 DAS_Tool && sh -c "R CMD INSTALL ./package/DASTool*"

ENV PATH=/kb/module/bin/DAS_Tool-1.1.2:$PATH
ENV PATH=/kb/module/lib/kb_das_tool/bin/bbmap:$PATH

COPY ./ /kb/module
RUN mkdir -p /kb/module/work
RUN chmod -R a+rw /kb/module

WORKDIR /kb/module

RUN make all

ENTRYPOINT [ "./scripts/entrypoint.sh" ]

CMD [ ]
