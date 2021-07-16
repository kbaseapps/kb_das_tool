import errno
import json
import os
import subprocess
import sys
import time
import uuid
import zipfile
import shutil

from Bio import SeqIO

from installed_clients.AssemblyUtilClient import AssemblyUtil
from installed_clients.DataFileUtilClient import DataFileUtil
from installed_clients.KBaseReportClient import KBaseReport
from installed_clients.MetagenomeUtilsClient import MetagenomeUtils
from installed_clients.ReadsUtilsClient import ReadsUtils


def log(message, prefix_newline=False):
    """Logging function, provides a hook to suppress or redirect log messages."""
    print(('\n' if prefix_newline else '') + '{0:.2f}'.format(time.time()) + ': ' + str(message))


class DASToolUtil:
    DASTOOL_THREADS=2
    BINNER_RESULT_DIRECTORY = 'das_tool_output_dir'
    BINNER_BIN_RESULT_DIR = 'das_tool_output_dir_DASTool_bins'


    def __init__(self, config):
        self.callback_url = config['SDK_CALLBACK_URL']
        self.scratch = config['scratch']
        self.shock_url = config['shock-url']
        self.ws_url = config['workspace-url']
        self.dfu = DataFileUtil(self.callback_url)
        self.ru = ReadsUtils(self.callback_url)
        self.au = AssemblyUtil(self.callback_url)
        self.mgu = MetagenomeUtils(self.callback_url)



    def validate_run_das_tool_params(self, params):
        """
        validate_run_concoct_params:
                validates params passed to run_concoct method

        """
        log('Start validating run_kb_das_tool params')

        # check for required parameters
        for p in ['assembly_ref', 'input_binned_contig_names', 'output_binned_contig_name', 'workspace_name']:
            if p not in params:
                raise ValueError('"{}" parameter is required, but missing'.format(p))

    def mkdir_p(self, path):
        """
        mkdir_p: make directory for given path
        """
        if not path:
            return
        try:
            os.makedirs(path)
        except OSError as exc:
            if exc.errno == errno.EEXIST and os.path.isdir(path):
                pass
            else:
                raise

    def run_command(self, command):
        """
        run_command: run command and print result
        """
        #os.chdir(self.scratch)
        log('Start executing command:\n{}'.format(command))
        log('Command is running from:\n{}'.format(self.scratch))
        pipe = subprocess.Popen(command, stdout=subprocess.PIPE, shell=True)
        output,stderr = pipe.communicate()
        exitCode = pipe.returncode

        if (exitCode == 0):
            log('Executed command:\n{}\n'.format(command) +
                'Exit Code: {}\n'.format(exitCode))
        else:
            error_msg = 'Error running command:\n{}\n'.format(command)
            error_msg += 'Exit Code: {}\nOutput:\n{}\nStderr:\n{}'.format(exitCode, output, stderr)
            raise ValueError(error_msg)
            sys.exit(1)
        return (output,stderr)



    def get_contig_file(self, assembly_ref):
        """
        get_contig_file: get contif file from GenomeAssembly object
        """

        contig_file = self.au.get_assembly_as_fasta({'ref': assembly_ref}).get('path')

        sys.stdout.flush()
        contig_file = self.dfu.unpack_file({'file_path': contig_file})['file_path']

        return contig_file


    def retrieve_and_clean_assembly(self, task_params):
        if os.path.exists(task_params['contig_file_path']):
            assembly =  task_params['contig_file_path']
            print("FOUND ASSEMBLY ON LOCAL SCRATCH")
        else:
            # we are on njsw so lets copy it over to scratch
            assembly = self.get_contig_file(task_params['assembly_ref'])

        # remove spaces from fasta headers because that breaks bedtools
        assembly_clean = os.path.abspath(assembly).split('.fa')[0] + "_clean.fa"

        command = '/bin/bash reformat.sh in={} out={} addunderscore'.format(assembly,assembly_clean)

        log('running reformat command: {}'.format(command))
        out,err = self.run_command(command)

        return assembly_clean



    def generate_output_file_list(self, result_directory):
        """
        generate_output_file_list: zip result files and generate file_links for report
        """
        log('Start packing result files')
        output_files = list()

        output_directory = os.path.join(self.scratch, str(uuid.uuid4()))
        self.mkdir_p(output_directory)
        result_file = os.path.join(output_directory, 'das_tool_result.zip')
        report_file = None

        with zipfile.ZipFile(result_file, 'w',
                             zipfile.ZIP_DEFLATED,
                             allowZip64=True) as zip_file:

            # grab all files we want to zip
            for dirname, subdirs, files in os.walk(result_directory):
                for file in files:
                    if (file.endswith('.sam') or
                        file.endswith('.bam') or
                        file.endswith('.bai') or
                       file.endswith('.summary')):
                            continue
                    if (dirname.endswith(self.BINNER_BIN_RESULT_DIR)):
                            continue
                    zip_file.write(os.path.join(dirname, file), file)
                if (dirname.endswith(self.BINNER_BIN_RESULT_DIR)):
                    baseDir = os.path.basename(dirname)
                    for file in files:
                        full = os.path.join(dirname, file)
                        zip_file.write(full, os.path.join(baseDir, file))


        output_files.append({'path': result_file,
                             'name': os.path.basename(result_file),
                             'label': os.path.basename(result_file),
                             'description': 'Files generated by kb_das_tool App'})

        return output_files

    def generate_html_report(self, result_directory, assembly_ref, binned_contig_obj_ref):
        """
        generate_html_report: generate html summary report
        """

        log('Start generating html report')
        #html_report = list()

        output_directory = os.path.join(self.scratch, 'html_dir_' + str(uuid.uuid4()))
        self.mkdir_p(output_directory)
        result_file_path = os.path.join(output_directory, 'report.html')

        # get summary data from existing assembly object and bins_objects
        Summary_Table_Content = ''
        Overview_Content = ''
        (binned_contig_count, input_contig_count,
         total_bins_count) = self.generate_overview_info(assembly_ref,
                                                          binned_contig_obj_ref,
                                                          result_directory)

        # get pdfs
        pdf_filename_l = [f for f in os.listdir(self.BINNER_RESULT_DIRECTORY) if f.endswith('.pdf')]
        assert len(pdf_filename_l) == 2


        Overview_Content += '<p>Binned contigs: {}</p>'.format(binned_contig_count)
        Overview_Content += '<p>Input contigs: {}</p>'.format(input_contig_count)
        Overview_Content += '<p>Number of bins: {}</p>'.format(total_bins_count)
        for pdf_filename in pdf_filename_l:
            Overview_Content += '\n<embed src="{}" width="1000px" height="700px">'.format(pdf_filename)

        with open(result_file_path, 'w') as result_file:
            with open(os.path.join(os.path.dirname(__file__), 'report_template.html'),
                      'r') as report_template_file:
                report_template = report_template_file.read()
                report_template = report_template.replace('<p>Overview_Content</p>',
                                                          Overview_Content)
                report_template = report_template.replace('Summary_Table_Content',
                                                          Summary_Table_Content)
                result_file.write(report_template)

        # copy pdfs into html dir
        for pdf_filename in pdf_filename_l:
            shutil.copyfile(os.path.join(self.BINNER_RESULT_DIRECTORY, pdf_filename), os.path.join(output_directory, pdf_filename))

        # save html dir to shock
        def dir_to_shock(dir_path, name, description):
            '''
            For regular directories or html directories

            name - for regular directories: the name of the flat (zip) file returned to ui
                   for html directories: the name of the html file
            '''
            dfu_fileToShock_ret = self.dfu.file_to_shock({
                'file_path': dir_path,
                'make_handle': 0,
                'pack': 'zip',
                })

            dir_shockInfo = {
                'shock_id': dfu_fileToShock_ret['shock_id'],
                'name': name,
                'description': description
                }

            return dir_shockInfo

        html_shockInfo = dir_to_shock(output_directory, 'report.html', 'Report html for DAS tool')

        """
        html_report.append({'path': result_file_path,
                            'name': os.path.basename(result_file_path),
                            'label': os.path.basename(result_file_path),
                            'description': 'HTML summary report for kb_concoct App'})

        return html_report
        """

        return [html_shockInfo]


    def generate_overview_info(self, assembly_ref, binned_contig_obj_ref, result_directory):
        """
        _generate_overview_info: generate overview information from assembly and binnedcontig
        """

        # get assembly and binned_contig objects that already have some data populated in them
        assembly = self.dfu.get_objects({'object_refs': [assembly_ref]})['data'][0]
        binned_contig = self.dfu.get_objects({'object_refs': [binned_contig_obj_ref]})['data'][0]

        input_contig_count = assembly.get('data').get('num_contigs')
        bins_directory = os.path.join(self.scratch, result_directory, self.BINNER_BIN_RESULT_DIR)
        binned_contig_count = 0
        total_bins_count = 0
        total_bins = binned_contig.get('data').get('bins')
        total_bins_count = len(total_bins)
        for bin in total_bins:
            binned_contig_count += len(bin.get('contigs'))

        return (binned_contig_count, input_contig_count, total_bins_count)


    def generate_report(self, binned_contig_obj_ref, params):
        """
        generate_report: generate summary report

        """
        log('Generating report')
        params['result_directory'] = self.BINNER_RESULT_DIRECTORY

        output_files = self.generate_output_file_list(params['result_directory'])

        output_html_files = self.generate_html_report(params['result_directory'],
                                                       params['assembly_ref'],
                                                       binned_contig_obj_ref)

        report_params = {
              'message': '',
              'workspace_name': params.get('workspace_name'),
              'file_links': output_files,
              'html_links': output_html_files,
              'direct_html_link_index': 0,
              'html_window_height': 500,
              'report_object_name': 'kb_das_tool_report_' + str(uuid.uuid4())}

        kbase_report_client = KBaseReport(self.callback_url)
        output = kbase_report_client.create_extended_report(report_params)

        report_output = {'report_name': output['name'], 'report_ref': output['ref']}

        return report_output

    def rename_and_standardize_bin_names(self):
        """
        generate_command: generate renamed bins
        """
        log("\n\nRunning rename_and_standardize_bin_names")
        i = 0
        path_to_result_bins = os.path.join(self.scratch, self.BINNER_RESULT_DIRECTORY, "das_tool_output_dir_DASTool_bins")
        path_to_das_tool_key = os.path.abspath(path_to_result_bins) + '/das_tool_name_key.tsv'
        with open(path_to_das_tool_key, 'w+') as f:
            f.write("Original.Bin.Name\tRenamed.Bin.Name\n")
            for dirname, subdirs, files in os.walk(path_to_result_bins):
                for file in files:
                    if file.endswith('.fa'):
                        i += 1
                        os.rename(os.path.abspath(path_to_result_bins) + '/' + file,
                                  os.path.abspath(path_to_result_bins) + '/bin.' + str(i).zfill(3) + '.fasta')  # need to change to 4 digits
                        f.write(file + '\tbin.' + str(i).zfill(3) + '.fasta\n')

    def make_binned_contig_summary_file_for_binning_apps(self, task_params):
        """
        generate_command: generate binned contig summary command
        """
        log("\n\nRunning make_binned_contig_summary_file_for_binning_apps")
        result_directory = task_params['result_directory']
        path_to_result_bins = '{}/{}/'.format(result_directory, task_params['bin_result_directory'])
        path_to_summary_file = path_to_result_bins + 'binned_contig.summary'
        with open(path_to_summary_file, 'w+') as f:
            f.write("Bin name\tCompleteness\tGenome size\tGC content\n")
            for dirname, subdirs, files in os.walk(path_to_result_bins):
                for file in files:
                    if file.endswith('.fasta'):
                        genome_bin_fna_file = os.path.join(path_to_result_bins, file)
                        bbstats_output_file = os.path.join(self.scratch, self.BINNER_RESULT_DIRECTORY,
                                                           genome_bin_fna_file).split('.fasta')[0] + ".bbstatsout"
                        bbstats_output = self.generate_stats_for_genome_bins(task_params,
                                                                             genome_bin_fna_file,
                                                                             bbstats_output_file)
                        f.write('{}\t0\t{}\t{}\n'.format(genome_bin_fna_file.split("/")[-1],
                                                         bbstats_output['contig_bp'],
                                                         bbstats_output['gc_avg']))
        log('Finished make_binned_contig_summary_file_for_binning_apps function')

    #
    # def make_binned_contig_summary_file_for_binning_apps(self, task_params):
    #     """
    #     generate_command: generate binned contig summary command
    #     """
    #     log("\n\nRunning make_binned_contig_summary_file_for_binning_apps")
    #     path_to_result = os.path.join(self.scratch, self.BINNER_RESULT_DIRECTORY, "das_tool_output_dir_DASTool_bins")
    #     path_to_summary_file = path_to_result + '/binned_contig.summary'
    #     with open(path_to_summary_file, 'w+') as f:
    #         f.write("Bin name\tCompleteness\tGenome size\tGC content\n")
    #         for dirname, subdirs, files in os.walk(path_to_result):
    #             for file in files:
    #                 if file.endswith('.fasta'):
    #                     genome_bin_fna_file = os.path.join(path_to_result, file)
    #                     bbstats_output_file = os.path.join(path_to_result,
    #                                                        genome_bin_fna_file).split('.fasta')[0] + ".bbstatsout"
    #                     bbstats_output = self.generate_stats_for_genome_bins(task_params,
    #                                                                          genome_bin_fna_file,
    #                                                                          bbstats_output_file)
    #                     f.write('{}\t0\t{}\t{}\n'.format(genome_bin_fna_file.split("/")[-1],
    #                                                      bbstats_output['contig_bp'],
    #                                                      bbstats_output['gc_avg']))
    #     f.close()
    #     log('Finished make_binned_contig_summary_file_for_binning_apps function')
    #

    def generate_stats_for_genome_bins(self, task_params, genome_bin_fna_file, bbstats_output_file):
        """
        generate_command: bbtools stats.sh command
        """
        log("running generate_stats_for_genome_bins on {}".format(genome_bin_fna_file))
        genome_bin_fna_file = os.path.join(self.scratch, self.BINNER_RESULT_DIRECTORY, genome_bin_fna_file)
        command = '/bin/bash stats.sh in={} format=3 > {}'.format(genome_bin_fna_file, bbstats_output_file)
        self.run_command(command)
        bbstats_output = open(bbstats_output_file, 'r').readlines()[1]
        n_scaffolds = bbstats_output.split('\t')[0]
        n_contigs = bbstats_output.split('\t')[1]
        scaf_bp = bbstats_output.split('\t')[2]
        contig_bp = bbstats_output.split('\t')[3]
        gap_pct = bbstats_output.split('\t')[4]
        scaf_N50 = bbstats_output.split('\t')[5]
        scaf_L50 = bbstats_output.split('\t')[6]
        ctg_N50 = bbstats_output.split('\t')[7]
        ctg_L50 = bbstats_output.split('\t')[8]
        scaf_N90 = bbstats_output.split('\t')[9]
        scaf_L90 = bbstats_output.split('\t')[10]
        ctg_N90 = bbstats_output.split('\t')[11]
        ctg_L90 = bbstats_output.split('\t')[12]
        scaf_max = bbstats_output.split('\t')[13]
        ctg_max = bbstats_output.split('\t')[14]
        scaf_n_gt50K = bbstats_output.split('\t')[15]
        scaf_pct_gt50K = bbstats_output.split('\t')[16]
        gc_avg = float(bbstats_output.split('\t')[17]) * 100  # need to figure out if correct
        gc_std = float(bbstats_output.split('\t')[18]) * 100  # need to figure out if correct

        log('Generated generate_stats_for_genome_bins command: {}'.format(command))

        return {'n_scaffolds': n_scaffolds,
                'n_contigs': n_contigs,
                'scaf_bp': scaf_bp,
                'contig_bp': contig_bp,
                'gap_pct': gap_pct,
                'scaf_N50': scaf_N50,
                'scaf_L50': scaf_L50,
                'ctg_N50': ctg_N50,
                'ctg_L50': ctg_L50,
                'scaf_N90': scaf_N90,
                'scaf_L90': scaf_L90,
                'ctg_N90': ctg_N90,
                'ctg_L90': ctg_L90,
                'scaf_max': scaf_max,
                'ctg_max': ctg_max,
                'scaf_n_gt50K': scaf_n_gt50K,
                'scaf_pct_gt50K': scaf_pct_gt50K,
                'gc_avg': gc_avg,
                'gc_std': gc_std
                }

    def generate_das_tool_input_files_and_commands_from_binned_contigs(self, params):
        #params['binned_contig_list_file'] = binned_contig_list_file
        binned_contig_names = params['input_binned_contig_names']
        trimmed_binned_contig_name_list = []
        contig_to_bin_file_name_list = []
        for input_ref in binned_contig_names:
            # next line needed for testing
            # binned_contig = self.dfu.get_objects({'object_refs': [input_ref['binned_contig_obj_ref']]})['data'][0]

            # next line needed in production only
            binned_contig = self.dfu.get_objects({'object_refs': [input_ref]})['data'][0]
            binned_contig_name = binned_contig.get('info')[1]
            binned_contig_data = binned_contig.get('data')
            bins = binned_contig_data.get('bins')
            trimmed_binned_contig_name = binned_contig_name.split(".BinnedContig")[0]
            trimmed_binned_contig_name_list.append(trimmed_binned_contig_name)
            contig_to_bin_file_name = "{}_contigs_to_bins.tsv".format(trimmed_binned_contig_name)
            contig_to_bin_file_name_list.append(contig_to_bin_file_name)


            f = open(contig_to_bin_file_name, "w+")
            for bin in bins:
                bin_id = bin.get('bid')
                trimmed_bin_id = bin_id.split(".fasta")[0]
                contigs = bin.get('contigs')
                for contig_id, contig_value in contigs.items():
                    f.write("{}\t{}.{}\n".format(contig_id, trimmed_binned_contig_name, trimmed_bin_id))
            f.close()
        #contig_to_bin_file_name_list = self.BINNER_RESULT_DIRECTORY + contig_to_bin_file_name
        # temp = str(self.BINNER_RESULT_DIRECTORY) + '/'
        # contig_to_bin_file_name_list = [temp + s for s in contig_to_bin_file_name_list]

        return (trimmed_binned_contig_name_list, contig_to_bin_file_name_list)


    def generate_das_tool_command(self, params, trimmed_binned_contig_name_list, contig_to_bin_file_name_list):
        """
        generate_command: generate concoct params
        """

        print("\n\nRunning generate_das_tool_command")

        command = 'DAS_Tool '

        command += '-i {} '.format(contig_to_bin_file_name_list)
        command += '-l {} '.format(trimmed_binned_contig_name_list)
        command += '-c {} '.format(params.get('contig_file_path'))
        command += '-o {} '.format(self.BINNER_RESULT_DIRECTORY)
        command += '--search_engine {} '.format(params.get('search_engine'))
        command += '--score_threshold {} '.format(params.get('score_threshold'))
        command += '--duplicate_penalty {} '.format(params.get('duplicate_penalty'))
        command += '--megabin_penalty {} '.format(params.get('megabin_penalty'))
        command += '--write_bin_evals {} '.format(params.get('write_bin_evals'))
        command += '--create_plots {} '.format(params.get('create_plots'))
        command += '--write_bins 1 '
        command += '--write_unbinned 0 '
        command += '-t {}'.format(self.DASTOOL_THREADS)

        log('Generated das_tool command: {}'.format(command))

        return command

    def run_das_tool(self, params):
        """
        run_das_tool: DAS_Tool app

        required params:
            assembly_ref: Metagenome assembly object reference
            input_binned_contig_names: list of BinnedContig objects
            output_binned_contig_name: output BinnedContig object name
            workspace_name: the name of the workspace it gets saved to.

        optional params:
            search_engine; default diamond
            score_threshold; default 0.5
            duplicate_penalty; default 0.6
            megabin_penalty; default 0.5
            write_bin_evals; default 1
            create_plots; default 1
            write_bins; default 1
            write_unbinned; default 0

        ref: https://github.com/cmks/DAS_Tool
        """
        log('--->\nrunning DASToolUtil.run_das_tool\n' +
            'params:\n{}'.format(json.dumps(params, indent=1)))

        self.validate_run_das_tool_params(params)

        print("\n\nFinished running validate_run_das_tool_params")
        #
        contig_file = self.get_contig_file(params.get('assembly_ref'))
        params['contig_file_path'] = contig_file

        result_directory = os.path.join(self.scratch, self.BINNER_RESULT_DIRECTORY)
        params['result_directory'] = result_directory
        self.mkdir_p(result_directory)

        cwd = os.getcwd()
        log('Changing working dir to {}'.format(result_directory))
        os.chdir(result_directory)

        (trimmed_binned_contig_name_list, contig_to_bin_file_name_list) = self.generate_das_tool_input_files_and_commands_from_binned_contigs(params)
        comma_symbol = ','
        trimmed_binned_contig_name_list = comma_symbol.join(trimmed_binned_contig_name_list)
        contig_to_bin_file_name_list = comma_symbol.join(contig_to_bin_file_name_list)

        log(os.listdir(result_directory))
        log("trimmed_binned_contig_name_list {}".format(trimmed_binned_contig_name_list))
        log("contig_to_bin_file_name_list {}".format(contig_to_bin_file_name_list))

            # binned_contig_to_file_params = {
            #     'input_ref': input_ref['binned_contig_obj_ref'],
            #     'save_to_shock': 1,
            #     'bin_file_directory': '{}/bin_set_{}/'.format(result_directory, i),
            #     'workspace_name': params.get('workspace_name'),
            # }
            #
            # self.mgu.binned_contigs_to_file(binned_contig_to_file_params) # returns "binned_contig_obj_ref" of type "obj_ref" (An X/Y/Z style reference)


            #shutil.copytree(bin_file_directory, os.path.join(result_directory, bin_file_directory))
            #print('\n\n\n result: {}'.format(self.mgu.binned_contigs_to_file(binned_contig_to_file_params)))

        #run concoct
        command = self.generate_das_tool_command(params, trimmed_binned_contig_name_list, contig_to_bin_file_name_list)
        log('\nWorking dir is {}'.format(result_directory))
        log('\nWorking dir is {}'.format(os.getcwd()))
        log('Changing working dir to {}'.format(result_directory))
        os.chdir(result_directory)
        self.run_command(command)

        os.chdir(self.scratch)

        task_params = {}
        task_params['result_directory'] = os.path.join(self.scratch)
        task_params['bin_result_directory'] = os.path.join(self.BINNER_RESULT_DIRECTORY , "das_tool_output_dir_DASTool_bins")

        # check to make sure bins were generated, otherwise no need to run the rest
        if not os.path.exists(task_params['bin_result_directory']):
            log('DAS_Tool did not succeed in generating a set of bins using the input bins and parameters - skipping the creation of a new BinnedContig object.')
            log('Note: this result is sometimes expected using the DAS-Tool workflow; it is possible that DAS-Tool cannot optimize the input binned contigs.')
            log('KBase is aware of this error!')
            log('Currently KBase manages this run instance as an error because KBase is expecting an output set of binned contigs.')
            raise ValueError('No bins generated - this is one of the expected results when DAS-Tool cannot optimize the input bins, and not necessarily an error. KBase is aware of the issue where DAS-Tool runs successfully but does not produce any output set of optimized bins.')
        else:
            self.rename_and_standardize_bin_names()
            self.make_binned_contig_summary_file_for_binning_apps(task_params)

            generate_binned_contig_param = {
                'file_directory': os.path.join(self.scratch, self.BINNER_RESULT_DIRECTORY , "das_tool_output_dir_DASTool_bins"),
                'assembly_ref': params.get('assembly_ref'),
                'binned_contig_name': params.get('output_binned_contig_name'),
                'workspace_name': params.get('workspace_name')
            }

            binned_contig_obj_ref = self.mgu.file_to_binned_contigs(
                                        generate_binned_contig_param).get('binned_contig_obj_ref')

            reportVal = self.generate_report(binned_contig_obj_ref, params)

            returnVal = {
                'result_directory': os.path.join(self.scratch, self.BINNER_RESULT_DIRECTORY),
                'binned_contig_obj_ref': binned_contig_obj_ref
            }

            returnVal.update(reportVal)

        return returnVal
