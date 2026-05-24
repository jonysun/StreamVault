package com.flower.spirit.entity;

import java.io.Serializable;
import java.util.Date;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.TableGenerator;

@Entity
@Table(name = "biz_author_name_history")
public class AuthorNameHistoryEntity implements Serializable {

	private static final long serialVersionUID = 5301557841756241718L;

	@Id
	@GeneratedValue(strategy = GenerationType.TABLE, generator = "biz_author_name_history_seq")
	@TableGenerator(name = "biz_author_name_history_seq", allocationSize = 1, table = "seq_common", pkColumnName = "seq_id", valueColumnName = "seq_count")
	private Integer id;

	private Integer authorprofileid;

	private String displayname;

	private Date firstseentime;

	private Date lastseentime;

	public Integer getId() {
		return id;
	}

	public void setId(Integer id) {
		this.id = id;
	}

	public Integer getAuthorprofileid() {
		return authorprofileid;
	}

	public void setAuthorprofileid(Integer authorprofileid) {
		this.authorprofileid = authorprofileid;
	}

	public String getDisplayname() {
		return displayname;
	}

	public void setDisplayname(String displayname) {
		this.displayname = displayname;
	}

	public Date getFirstseentime() {
		return firstseentime;
	}

	public void setFirstseentime(Date firstseentime) {
		this.firstseentime = firstseentime;
	}

	public Date getLastseentime() {
		return lastseentime;
	}

	public void setLastseentime(Date lastseentime) {
		this.lastseentime = lastseentime;
	}
}
