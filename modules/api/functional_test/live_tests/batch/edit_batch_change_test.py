from hamcrest import *
from utils import *
import datetime

@pytest.mark.manual_batch_review
def test_edit_batch_change_with_new_scheduled_time_succeeds(shared_zone_test_context):
    """
    Test successfully changing the time of a scheduled batch change
    """
    client = shared_zone_test_context.ok_vinyldns_client
    original_dt = (datetime.datetime.now() + datetime.timedelta(days=1)).strftime('%Y-%m-%dT%H:%M:%SZ')
    new_dt = (datetime.datetime.now() + datetime.timedelta(days=2)).strftime('%Y-%m-%dT%H:%M:%SZ')

    original_batch_change_input = {
        "comments": "this is optional",
        "changes": [
            get_change_A_AAAA_json("parent.com.", address="4.5.6.7"),
        ],
        "scheduledTime": original_dt,
        "ownerGroupId": shared_zone_test_context.ok_group['id']
    }
    new_batch_change_input = {
        "comments": "this is optional",
        "changes": [
            get_change_A_AAAA_json("parent.com.", address="4.5.6.7"),
        ],
        "scheduledTime": new_dt,
        "ownerGroupId": shared_zone_test_context.ok_group['id']
    }
    result = None
    try:
        result = client.create_batch_change(original_batch_change_input, status=202)
        assert_that(result['status'], 'Scheduled')
        assert_that(result['scheduledTime'], original_dt)

        retrieved_batch_change = client.edit_batch_change(result['id'], new_batch_change_input, status=202)

        assert_that(retrieved_batch_change['status'], 'Scheduled')
        assert_that(retrieved_batch_change['scheduledTime'], new_dt)

    finally:
        if result:
            rejecter = shared_zone_test_context.support_user_client
            rejecter.reject_batch_change(result['id'], status=200)
