package service

import scala.slick.driver.H2Driver.simple._
import Database.threadLocalSession

import model._

trait PullRequestService { self: IssuesService =>

  def getPullRequest(owner: String, repository: String, issueId: Int): Option[(Issue, PullRequest)] = {
    val issue = getIssue(owner, repository, issueId.toString)
    if(issue.isDefined){
      Query(PullRequests).filter(_.byPrimaryKey(owner, repository, issueId)).firstOption match {
        case Some(pullreq) => Some((issue.get, pullreq))
        case None          => None
      }
    } else None
  }

  def createPullRequest(originUserName: String, originRepositoryName: String, issueId: Int,
        originBranch: String, requestUserName: String, requestRepositoryName: String, requestBranch: String): Unit =
    PullRequests insert (PullRequest(
      originUserName,
      originRepositoryName,
      issueId,
      originBranch,
      requestUserName,
      requestRepositoryName,
      requestBranch,
      None,
      None))

  def mergePullRequest(originUserName: String, originRepositoryName: String, issueId: Int,
        mergeStartId: String, mergeEndId: String): Unit = {
    Query(PullRequests)
      .filter(_.byPrimaryKey(originUserName, originRepositoryName, issueId))
      .map(t => t.mergeStartId ~ t.mergeEndId)
      .update(mergeStartId, mergeEndId)
  }

}