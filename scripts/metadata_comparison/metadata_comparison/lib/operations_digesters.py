
from abc import ABC, abstractmethod
import dateutil.parser
from metadata_comparison.lib.operation_ids import JsonObject, operation_id_to_api_version, PAPI_V1_API_VERSION, PAPI_V2_ALPHA1_API_VERSION, PAPI_V2_BETA_API_VERSION
from datetime import datetime


class OperationDigester(ABC):
    """
    Abstract Base Class for PAPI operation subclasses sharing an interface for the purpose of treating digesters
    uniformly regardless of PAPI version.
    """

    def __init__(self, operation_json: JsonObject):
        self.__json = operation_json

    def __metadata(self) -> JsonObject:
        return self.__json.get('metadata')

    def start_time(self) -> datetime:
        return dateutil.parser.parse(self.__metadata().get('createTime'))

    def end_time(self) -> datetime:
        return dateutil.parser.parse(self.__metadata().get('endTime'))

    @staticmethod
    def create(operation_json: JsonObject):
        operation_id = operation_json.get('name')
        version = operation_id_to_api_version(operation_id)
        if version == PAPI_V1_API_VERSION:
            return PapiV1OperationDigester(operation_json)
        elif version == PAPI_V2_ALPHA1_API_VERSION:
            return PapiV2AlphaOperationDigester(operation_json)
        elif version == PAPI_V2_BETA_API_VERSION:
            return PapiV2BetaOperationDigester(operation_json)
        else:
            raise ValueError(f"Unrecognized format for PAPI operation ID {operation_id}")


class PapiV1OperationDigester(OperationDigester):
    def __init__(self, operation_json: JsonObject):
        super(PapiV1OperationDigester, self).__init__(operation_json)


class PapiV2OperationDigester(OperationDigester, ABC):
    def __init__(self, operation_json: JsonObject):
        super(PapiV2OperationDigester, self).__init__(operation_json)


class PapiV2AlphaOperationDigester(PapiV2OperationDigester):
    def __init__(self, operation_json: JsonObject):
        super(PapiV2AlphaOperationDigester, self).__init__(operation_json)


class PapiV2BetaOperationDigester(PapiV2OperationDigester):
    def __init__(self, operation_json: JsonObject):
        super(PapiV2BetaOperationDigester, self).__init__(operation_json)
