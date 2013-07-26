package app

import util.{LockUtil, CollaboratorsAuthenticator, JGitUtil, ReferrerAuthenticator}
import util.Directory._
import util.Implicits._
import util.JGitUtil.{DiffInfo, CommitInfo}
import service._
import org.eclipse.jgit.api.Git
import jp.sf.amateras.scalatra.forms._
import org.eclipse.jgit.transport.RefSpec
import org.apache.commons.io.FileUtils
import scala.collection.JavaConverters._

class PullRequestsController extends PullRequestsControllerBase
  with RepositoryService with AccountService with IssuesService with PullRequestService with MilestonesService with ActivityService
  with ReferrerAuthenticator with CollaboratorsAuthenticator

trait PullRequestsControllerBase extends ControllerBase {
  self: RepositoryService with IssuesService with MilestonesService with ActivityService with PullRequestService
    with ReferrerAuthenticator with CollaboratorsAuthenticator =>

  val pullRequestForm = mapping(
    "title"           -> trim(label("Title"  , text(required, maxlength(100)))),
    "content"         -> trim(label("Content", optional(text()))),
    "branch"          -> trim(text(required, maxlength(100))),
    "requestUserName" -> trim(text(required, maxlength(100))),
    "requestCommitId" -> trim(text(required, maxlength(100))),
    "commitIdFrom"    -> trim(text(required, maxlength(40))),
    "commitIdTo"      -> trim(text(required, maxlength(40)))
  )(PullRequestForm.apply)

  val mergeForm = mapping(
    "message" -> trim(label("Message", text(required)))
  )(MergeForm.apply)

  case class PullRequestForm(
    title: String,
    content: Option[String],
    branch: String,
    requestUserName: String,
    requestBranch: String,
    commitIdFrom: String,
    commitIdTo: String)

  case class MergeForm(message: String)

  get("/:owner/:repository/pulls")(referrersOnly { repository =>
    pulls.html.list(repository)
  })

  get("/:owner/:repository/pulls/:id")(referrersOnly { repository =>
    val owner   = repository.owner
    val name    = repository.name
    val issueId = params("id").toInt

    getPullRequest(owner, name, issueId) map { case(issue, pullreq) =>
      JGitUtil.withGit(getRepositoryDir(owner, name)){ git =>
        val requestCommitId = git.getRepository.resolve(pullreq.requestBranch)

        val (commits, diffs) =
          getRequestCompareInfo(owner, name, pullreq.commitIdFrom, owner, name, pullreq.commitIdTo)

        pulls.html.pullreq(
          issue, pullreq,
          getComments(owner, name, issueId.toInt),
          (getCollaborators(owner, name) :+ owner).sorted,
          getMilestones(owner, name),
          commits,
          diffs,
          requestCommitId.getName,
          if(issue.closed){
            false
          } else {
            checkConflict(owner, name, pullreq.branch, owner, name, pullreq.requestBranch)
          },
          hasWritePermission(owner, name, context.loginAccount),
          repository,
          s"${baseUrl}${context.path}/git/${pullreq.requestUserName}/${pullreq.requestRepositoryName}.git")
      }

    } getOrElse NotFound
  })

  post("/:owner/:repository/pulls/:id/merge", mergeForm)(collaboratorsOnly { (form, repository) =>
    LockUtil.lock(s"${repository.owner}/${repository.name}/merge"){
      val issueId = params("id").toInt

      getPullRequest(repository.owner, repository.name, issueId).map { case (issue, pullreq) =>
        val remote = getRepositoryDir(repository.owner, repository.name)
        val tmpdir = new java.io.File(getTemporaryDir(repository.owner, repository.name), s"merge-${issueId}")
        val git = Git.cloneRepository.setDirectory(tmpdir).setURI(remote.toURI.toString).call

        try {
          // TODO mark issue as 'merged'
          val loginAccount = context.loginAccount.get
          createComment(repository.owner, repository.name, loginAccount.userName, issueId, "Closed", "close")
          updateClosed(repository.owner, repository.name, issueId, true)
          recordMergeActivity(repository.owner, repository.name, loginAccount.userName, issueId, form.message)

          git.checkout.setName(pullreq.branch).call

          git.fetch
            .setRemote(getRepositoryDir(repository.owner, repository.name).toURI.toString)
            .setRefSpecs(new RefSpec(s"refs/pull/${issueId}/head:refs/heads/${pullreq.branch}")).call

          val result = git.merge
            .include(git.getRepository.resolve("FETCH_HEAD"))
            .setCommit(false).call

          if(result.getConflicts != null){
            throw new RuntimeException("This pull request can't merge automatically.")
          }

          // TODO merge commit
  //        git.commit
  //          .setCommitter(new PersonIdent(loginAccount.userName, loginAccount.mailAddress))
  //          .setMessage(s"Merge pull request #${issueId} from ${pullreq.requestUserName}/${pullreq.requestRepositoryName}\n"
  //                     + form.message).call
          git.push.call

          val (commits, _) = getRequestCompareInfo(repository.owner, repository.name, pullreq.commitIdFrom,
            pullreq.requestUserName, pullreq.requestRepositoryName, pullreq.commitIdTo)

          commits.flatten.foreach { commit =>
            insertCommitId(repository.owner, repository.name, commit.id)
          }

          redirect(s"/${repository.owner}/${repository.name}/pulls/${issueId}")

        } finally {
          git.getRepository.close
          FileUtils.deleteDirectory(tmpdir)
        }
      } getOrElse NotFound
    }
  })

  private def checkConflict(userName: String, repositoryName: String, branch: String,
                            requestUserName: String, requestRepositoryName: String, requestBranch: String): Boolean = {
    LockUtil.lock(s"${userName}/${repositoryName}/merge-check"){
      val remote = getRepositoryDir(userName, repositoryName)
      val tmpdir = new java.io.File(getTemporaryDir(userName, repositoryName), "merge-check")
      if(tmpdir.exists()){
        FileUtils.deleteDirectory(tmpdir)
      }

      val git = Git.cloneRepository.setDirectory(tmpdir).setURI(remote.toURI.toString).call
      try {
        git.checkout.setName(branch).call

        git.fetch
          .setRemote(getRepositoryDir(requestUserName, requestRepositoryName).toURI.toString)
          .setRefSpecs(new RefSpec(s"refs/heads/${branch}:refs/heads/${requestBranch}")).call

        val result = git.merge
          .include(git.getRepository.resolve("FETCH_HEAD"))
          .setCommit(false).call

        result.getConflicts != null

      } finally {
        git.getRepository.close
        FileUtils.deleteDirectory(tmpdir)
      }
    }
  }

  get("/:owner/:repository/pulls/compare")(collaboratorsOnly { newRepo =>
    (newRepo.repository.originUserName, newRepo.repository.originRepositoryName) match {
      case (None,_)|(_, None) => NotFound // TODO BadRequest?
      case (Some(originUserName), Some(originRepositoryName)) => {
        getRepository(originUserName, originRepositoryName, baseUrl).map { oldRepo =>
          withGit(
            getRepositoryDir(originUserName, originRepositoryName),
            getRepositoryDir(params("owner"), params("repository"))
          ){ (oldGit, newGit) =>
            val oldBranch = JGitUtil.getDefaultBranch(oldGit, oldRepo).get._2
            val newBranch = JGitUtil.getDefaultBranch(newGit, newRepo).get._2

            redirect(s"${context.path}/${newRepo.owner}/${newRepo.name}/pulls/compare/${originUserName}:${oldBranch}...${newBranch}")
          }
        } getOrElse NotFound
      }
    }
  })

  get("/:owner/:repository/pulls/compare/*:*...*")(collaboratorsOnly { repository =>
    if(repository.repository.originUserName.isEmpty || repository.repository.originRepositoryName.isEmpty){
      NotFound // TODO BadRequest?
    } else {
      val originUserName       = repository.repository.originUserName.get
      val originRepositoryName = repository.repository.originRepositoryName.get

      getRepository(originUserName, originRepositoryName, baseUrl).map{ originRepository =>
        val Seq(compareUserName, compareFrom, compareTo) = multiParams("splat")

        withGit(
          getRepositoryDir(originUserName, originRepositoryName),
          getRepositoryDir(repository.owner, repository.name)
        ){ case (oldGit, newGit) =>

          val forkedId = getForkedCommitId(oldGit, newGit, originUserName, originRepositoryName, compareFrom,
            repository.owner, repository.name, compareTo)

          val oldId = oldGit.getRepository.resolve(forkedId)
          val newId = newGit.getRepository.resolve(compareTo)

          val (commits, diffs) = getRequestCompareInfo(
            compareUserName, repository.repository.originRepositoryName.get, forkedId,
            repository.owner, repository.name, compareTo)

          pulls.html.compare(commits, diffs, compareUserName, compareFrom, compareTo, oldId.getName, newId.getName,
            checkConflict(originUserName, originRepositoryName, compareFrom, repository.owner, repository.name, compareTo),
            repository, originRepository)
        }
      } getOrElse NotFound
    }
  })

  post("/:owner/:repository/pulls/new", pullRequestForm)(referrersOnly { (form, repository) =>
    val loginUserName = context.loginAccount.get.userName

    val issueId = createIssue(
      repository.owner,
      repository.name,
      loginUserName,
      form.title,
      form.content,
      None, None)

    createPullRequest(
      repository.owner,
      repository.name,
      issueId,
      form.branch,
      form.requestUserName,
      repository.name,
      form.requestBranch,
      form.commitIdFrom,
      form.commitIdTo)

    // fetch requested branch
    JGitUtil.withGit(getRepositoryDir(repository.owner, repository.name)){ git =>
      git.fetch
        .setRemote(getRepositoryDir(form.requestUserName, repository.name).toURI.toString)
        .setRefSpecs(new RefSpec(s"refs/heads/${form.requestBranch}:refs/pull/${issueId}/head"))
        .call
    }

    recordPullRequestActivity(repository.owner, repository.name, loginUserName, issueId, form.title)

    redirect(s"/${repository.owner}/${repository.name}/pulls/${issueId}")
  })

  private def withGit[T](oldDir: java.io.File, newDir: java.io.File)(action: (Git, Git) => T): T = {
    val oldGit = Git.open(oldDir)
    val newGit = Git.open(newDir)
    try {
      action(oldGit, newGit)
    } finally {
      oldGit.getRepository.close
      newGit.getRepository.close
    }
  }

  private def getForkedCommitId(oldGit: Git, newGit: Git, userName: String, repositoryName: String, branch: String,
      requestUserName: String, requestRepositoryName: String, requestBranch: String): String =
    JGitUtil.getCommitLogs(newGit, requestBranch, true){ commit =>
      existsCommitId(userName, repositoryName, commit.getName) &&
        JGitUtil.getBranchesOfCommit(oldGit, commit.getName).contains(branch)
    }.head.id

  private def getRequestCompareInfo(userName: String, repositoryName: String, branch: String,
      requestUserName: String, requestRepositoryName: String, requestCommitId: String): (Seq[Seq[CommitInfo]], Seq[DiffInfo]) = {

    withGit(
      getRepositoryDir(userName, repositoryName),
      getRepositoryDir(requestUserName, requestRepositoryName)
    ){ (oldGit, newGit) =>
      val oldId = oldGit.getRepository.resolve(branch)
      val newId = newGit.getRepository.resolve(requestCommitId)

      val commits = newGit.log.addRange(oldId, newId).call.iterator.asScala.map { revCommit =>
        new CommitInfo(revCommit)
      }.toList.splitWith{ (commit1, commit2) =>
        view.helpers.date(commit1.time) == view.helpers.date(commit2.time)
      }

      val diffs = JGitUtil.getDiffs(newGit, oldId.getName, newId.getName, true)

      (commits, diffs)
    }
  }

}