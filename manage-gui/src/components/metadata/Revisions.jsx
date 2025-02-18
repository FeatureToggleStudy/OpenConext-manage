import React from "react";
import I18n from "i18n-js";

import {DiffPatcher} from 'jsondiffpatch/src/diffpatcher';
import htmlFormatter from "jsondiffpatch/src/formatters/html";

import PropTypes from "prop-types";
import cloneDeep from "lodash.clonedeep";
import CheckBox from "../../components/CheckBox";
import ConfirmationDialog from "../../components/ConfirmationDialog";
import {escapeDeep, stop} from "../../utils/Utils";

import "jsondiffpatch/public/formatters-styles/html.css";
import "./Revisions.css";
import {restoreRevision} from "../../api";
import {setFlash} from "../../utils/Flash";

const ignoreInDiff = ["id", "eid", "revisionid", "user", "created", "ip", "revisionnote"];

export default class Revisions extends React.Component {

  constructor(props) {
    super(props);
    const {revisions} = this.props;
    this.state = {
      showRevisionDetails: revisions.reduce((acc, revision) => {
        acc[revision.id] = false;
        return acc;
      }, {}),
      showAllDetails: false,
      confirmationDialogOpen: false,
      confirmationDialogAction: () => this,
      cancelDialogAction: () => this.setState({confirmationDialogOpen: false}),
      confirmationQuestion: ""
    };
    this.differ = new DiffPatcher();
  }

  componentDidMount() {
    window.scrollTo(0, 0);
  }

  previousRevision = revision => this.props.revisions.find(rev => rev.revision.number === (revision.revision.number - 1));

  toggleAllShowDetail = e => {
    const showAll = e.target.checked;
    const newShowRevisionDetails = {...this.state.showRevisionDetails};
    Object.keys(newShowRevisionDetails).forEach(id => newShowRevisionDetails[id] = showAll);
    this.setState({showRevisionDetails: newShowRevisionDetails, showAllDetails: showAll});
  };

  toggleShowDetail = revision => {
    const newShowRevisionDetails = {...this.state.showRevisionDetails};
    newShowRevisionDetails[revision.id] = !newShowRevisionDetails[revision.id];
    this.setState({showRevisionDetails: newShowRevisionDetails});
  };

  doRestore = (id, revisionType, parentType, number) => () => {
    this.setState({confirmationDialogOpen: false});
    restoreRevision(id, revisionType, parentType).then(json => {
      if (json.exception) {
        setFlash(json.validations, "error");
      } else {
        const name = json.data.metaDataFields["name:en"] || json.data.metaDataFields["name:nl"] || "this service";
        setFlash(I18n.t("metadata.flash.restored", {
          name: name,
          revision: number,
          newRevision: json.revision.number
        }));
        this.props.history.replace(`/dummy`);
        setTimeout(() => this.props.history.replace(`/metadata/${json.type}/${json.id}/revisions`), 50);
      }
    });
  };

  restore = (id, number, revisionType, parentType, isLatest) => e => {
    stop(e);
    if (!isLatest) {
      this.setState({
        confirmationDialogOpen: true,
        confirmationQuestion: I18n.t("revisions.restoreConfirmation", {number: number}),
        confirmationDialogAction: this.doRestore(id, revisionType, parentType, number)
      });
    }
  };

  renderDiff = (revision, previous) => {
    const rev = cloneDeep(revision.data);
    escapeDeep(rev);
    ignoreInDiff.forEach(ignore => delete rev[ignore]);
    const prev = cloneDeep(previous.data);
    escapeDeep(prev);

    ignoreInDiff.forEach(ignore => delete prev[ignore]);

    const diffs = this.differ.diff(prev, rev);
    const html = htmlFormatter.format(diffs);
    //we need dangerouslySetInnerHTML otherwise the diff has to html in it, but the data is cleansed
    return diffs ? <p dangerouslySetInnerHTML={{__html: html}}/> : <p>{I18n.t("revisions.identical")}</p>
  };

  renderRevisionTable = (revision, isLatest, entityType) => {
    const showDetail = this.state.showRevisionDetails[revision.id];
    const headers = ["number", "created", "updatedBy", "status", "notes", "nope"];
    const isFirstRevision = revision.revision.number === 0;
    const restoreClassName = `button blue ${isLatest ? "grey" : ""}`;
    return (
      <table className="revision-table" key={revision.revision.number}>
        <thead>
        <tr>
          {headers.map((header, index) => <th key={index}
                                              className={header}>{I18n.t(`revisions.${header}`)}</th>)}
        </tr>
        </thead>
        <tbody>
        <tr>
          <td>{revision.revision.number}</td>
          <td>{new Date(revision.revision.created * 1000).toGMTString()}</td>
          <td>{revision.revision.updatedBy}</td>
          <td>{I18n.t(`metadata.${revision.data.state}`)}</td>
          <td>{revision.data.revisionnote || ""}</td>
          <td><a className={restoreClassName} href={`/restore/${revision.id}`}
                 onClick={this.restore(revision.id, revision.revision.number, revision.type, entityType, isLatest)}
                 disabled={isLatest}>
            {I18n.t("revisions.restore")}</a>
          </td>
        </tr>
        {!isFirstRevision &&
        <tr>
          <td colSpan={headers.length}><CheckBox name={revision.id} value={showDetail || false}
                                                 info={I18n.t("revisions.toggleDetails")}
                                                 onChange={() => this.toggleShowDetail(revision)}/></td>
        </tr>}
        {(showDetail && !isFirstRevision) &&
        <tr>
          <td className="diff"
              colSpan={headers.length}>{this.renderDiff(revision, this.previousRevision(revision))}</td>
        </tr>}
        </tbody>
      </table>
    );
  };

  render() {
    const {revisions, isNew, entityType} = this.props;
    const {
      showAllDetails, cancelDialogAction, confirmationDialogAction, confirmationDialogOpen,
      confirmationQuestion
    } = this.state;

    return (
      <div className="metadata-revisions">
        <ConfirmationDialog isOpen={confirmationDialogOpen}
                            cancel={cancelDialogAction}
                            confirm={confirmationDialogAction}
                            question={confirmationQuestion}/>
        {isNew && <div className="revisions-info">
          <h2>{I18n.t("revisions.noRevisions")}</h2>
        </div>}
        {!isNew && <div className="revisions-info">
          <h2>{I18n.t("revisions.info")}</h2>
          {revisions.length > 1 && <CheckBox name="toggleDiffs" value={showAllDetails || false}
                                             info={I18n.t("revisions.toggleAllDetails")}
                                             onChange={this.toggleAllShowDetail}/>}
        </div>}
        {!isNew && <div className="revisions">

          {revisions.map((rev, index) => this.renderRevisionTable(rev, index === 0, entityType))}
        </div>}
      </div>
    );
  }
}

Revisions.propTypes = {
  history: PropTypes.object.isRequired,
  revisions: PropTypes.array.isRequired,
  entityType: PropTypes.string.isRequired,
  isNew: PropTypes.bool.isRequired
};

