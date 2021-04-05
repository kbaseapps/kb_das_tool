# -*- coding: utf-8 -*-
#BEGIN_HEADER
import logging
import os
import json

from installed_clients.KBaseReportClient import KBaseReport
from kb_das_tool.Utils.DASToolUtil import DASToolUtil

#END_HEADER


class kb_das_tool:
    '''
    Module Name:
    kb_das_tool

    Module Description:
    A KBase module: kb_das_tool
    '''

    ######## WARNING FOR GEVENT USERS ####### noqa
    # Since asynchronous IO can lead to methods - even the same method -
    # interrupting each other, you must be *very* careful when using global
    # state. A method could easily clobber the state set by another while
    # the latter method is running.
    ######################################### noqa
    VERSION = "0.0.1"
    GIT_URL = ""
    GIT_COMMIT_HASH = ""

    #BEGIN_CLASS_HEADER
    #END_CLASS_HEADER

    # config contains contents of config file in a hash or None if it couldn't
    # be found


    # def __init__(self, config):
    #     #BEGIN_CONSTRUCTOR
    #     self.callback_url = os.environ['SDK_CALLBACK_URL']
    #     self.shared_folder = config['scratch']
    #     logging.basicConfig(format='%(created)s %(levelname)s: %(message)s',
    #                         level=logging.INFO)
    #     #END_CONSTRUCTOR
    #     pass

    def __init__(self, config):
        #BEGIN_CONSTRUCTOR
        self.config = config
        self.config['SDK_CALLBACK_URL'] = os.environ['SDK_CALLBACK_URL']
        self.config['KB_AUTH_TOKEN'] = os.environ['KB_AUTH_TOKEN']
        #END_CONSTRUCTOR
        pass

    def run_kb_das_tool(self, ctx, params):
        """
        :param params: instance of type "DASToolInputParams" (required
           params: assembly_ref: Genome assembly object reference
           binned_contig_names: BinnedContig object names and output file
           header workspace_name: the name of the workspace it gets saved to.
           ref: https://github.com/cmks/DAS_Tool) -> structure: parameter
           "assembly_ref" of type "obj_ref" (An X/Y/Z style reference),
           parameter "binned_contig_name" of String, parameter
           "workspace_name" of String, parameter "reads_list" of list of type
           "obj_ref" (An X/Y/Z style reference), parameter "thread" of Long,
           parameter "min_contig_length" of Long
        :returns: instance of type "DASToolResult" (result_folder: folder
           path that holds all files generated by run kb_das_tool report_name:
           report name generated by KBaseReport report_ref: report reference
           generated by KBaseReport) -> structure: parameter
           "result_directory" of String, parameter "binned_contig_obj_ref" of
           type "obj_ref" (An X/Y/Z style reference), parameter "report_name"
           of String, parameter "report_ref" of String
        """
        # ctx is the context object
        # return variables are: returnVal
        #BEGIN run_kb_das_tool

        print('--->\nRunning kb_das_tool.kb_das_tool\nparams:')
        print(json.dumps(params, indent=1))

        for key, value in params.items():
            if isinstance(value, str):
                params[key] = value.strip()

        das_tool_runner = DASToolUtil(self.config)

        returnVal = das_tool_runner.run_das_tool(params)
        #END run_kb_das_tool

        # At some point might do deeper type checking...
        if not isinstance(returnVal, dict):
            raise ValueError('Method run_kb_das_tool return value ' +
                             'returnVal is not type dict as required.')
        # return the results
        return [returnVal]

    def status(self, ctx):
        #BEGIN_STATUS
        returnVal = {'state': "OK",
                     'message': "",
                     'version': self.VERSION,
                     'git_url': self.GIT_URL,
                     'git_commit_hash': self.GIT_COMMIT_HASH}
        #END_STATUS
        return [returnVal]
